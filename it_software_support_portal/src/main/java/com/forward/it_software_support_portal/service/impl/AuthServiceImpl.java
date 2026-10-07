package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.dto.response.LoginResponse;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
import com.forward.it_software_support_portal.security.JwtTokenService;
import com.forward.it_software_support_portal.security.ratelimit.ClientIpResolver;
import com.forward.it_software_support_portal.security.ratelimit.LoginAttemptLimiter;
import com.forward.it_software_support_portal.security.ratelimit.TooManyLoginAttemptsException;
import com.forward.it_software_support_portal.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Email/password authentication against the {@code users} table.
 *
 * <p>Three properties worth noting:
 *
 * <ul>
 *   <li><strong>Uniform failure.</strong> Unknown email, wrong password, no password set and
 *       deactivated account all produce the same {@link InvalidCredentialsException}. The specific
 *       cause is logged, never returned.
 *   <li><strong>No timing shortcut.</strong> When no user or no hash is found the encoder still runs
 *       against a dummy hash, so a request for a nonexistent account costs roughly the same as one for
 *       a real account. Returning early would let an attacker enumerate accounts by response time.
 *   <li><strong>The password never leaves this method.</strong> It is not logged, not stored, and not
 *       echoed in any response.
 * </ul>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final ClientIpResolver clientIpResolver;
    /** Request-scoped proxy; used only to read the peer address for throttling. */
    private final HttpServletRequest httpServletRequest;

    /**
     * A hash of a random value nobody knows, verified against when the account does not exist so that
     * the request still costs a full password verification. Comparing any input against it fails.
     *
     * <p><strong>Produced by the injected encoder rather than written as a literal, deliberately.</strong>
     * The previous constant was a hand-written string that was one character too long to be a valid
     * BCrypt hash. {@code BCryptPasswordEncoder.matches} rejects a malformed hash on a regex before it
     * does any hashing, so it returned false immediately: the timing equalisation this field exists for
     * was not happening at all, and every login for an unknown email logged a warning. Deriving the
     * value from the encoder removes both the possibility of a malformed literal and a second, quieter
     * failure mode - a literal pinned at cost factor 10 stops matching the real verification cost the
     * moment the encoder's strength is raised.
     *
     * <p>Costs one BCrypt computation at startup, which is the point: it is the same computation a real
     * verification performs.
     */
    private final String dummyHash;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService,
                           LoginAttemptLimiter loginAttemptLimiter,
                           ClientIpResolver clientIpResolver,
                           HttpServletRequest httpServletRequest) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.clientIpResolver = clientIpResolver;
        this.httpServletRequest = httpServletRequest;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);

        // Checked before the account is even looked up, so a refusal costs no query and no BCrypt work,
        // and cannot depend on whether the account exists.
        guard(() -> loginAttemptLimiter.checkAllowed(request.getEmail(), clientIp),
                TooManyLoginAttemptsException.class);

        try {
            LoginResponse response = authenticate(request);
            guard(() -> loginAttemptLimiter.recordSuccess(request.getEmail(), clientIp), null);
            return response;
        } catch (InvalidCredentialsException e) {
            guard(() -> loginAttemptLimiter.recordFailure(request.getEmail(), clientIp), null);
            throw e;
        }
    }

    /**
     * Runs a limiter operation without letting a fault in it break authentication.
     *
     * <p><strong>Fail-safe policy.</strong> Throttling is protective, not authoritative: it decides
     * whether to <em>refuse</em> an attempt, never whether to <em>accept</em> one. So an unexpected fault
     * inside the limiter is logged at ERROR and swallowed, and the request proceeds to normal password
     * verification. The consequence is degraded brute-force protection, loudly reported — not an
     * authentication bypass, because the password is still verified, and not a 500 for an ordinary login,
     * because a tracking bug must not take authentication offline.
     *
     * <p>{@code TooManyLoginAttemptsException} is the limiter working correctly, so it is rethrown rather
     * than swallowed - hence the {@code expected} parameter.
     */
    private void guard(Runnable limiterOperation, Class<? extends RuntimeException> expected) {
        try {
            limiterOperation.run();
        } catch (RuntimeException e) {
            if (expected != null && expected.isInstance(e)) {
                throw e;
            }
            log.error("Login attempt limiter failed; continuing without throttling for this request. "
                    + "Brute-force protection is degraded until this is resolved.", e);
        }
    }

    private LoginResponse authenticate(LoginRequest request) {
        Optional<User> candidate = userRepository.findByEmail(request.getEmail());

        String storedHash = candidate
                .map(User::getPasswordHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .orElse(dummyHash);

        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);

        if (candidate.isEmpty()) {
            log.info("Login rejected: no account for the supplied email");
            throw invalidCredentials();
        }
        User user = candidate.get();

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            log.info("Login rejected for user {}: no password has been set", user.getId());
            throw invalidCredentials();
        }
        if (!passwordMatches) {
            log.info("Login rejected for user {}: incorrect password", user.getId());
            throw invalidCredentials();
        }
        if (!Boolean.TRUE.equals(user.getActive())) {
            log.info("Login rejected for user {}: account is deactivated", user.getId());
            throw invalidCredentials();
        }

        JwtTokenService.IssuedToken token = jwtTokenService.issue(user);
        log.info("Issued access token for user {} with role {}", user.getId(), user.getRole());

        return LoginResponse.builder()
                .accessToken(token.token())
                .tokenType("Bearer")
                .expiresIn(token.expiresIn())
                .userId(user.getId())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .build();
    }

    private static InvalidCredentialsException invalidCredentials() {
        return new InvalidCredentialsException("Invalid email or password");
    }
}
