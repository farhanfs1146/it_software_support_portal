package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.dto.request.RefreshTokenRequest;
import com.forward.it_software_support_portal.dto.response.CurrentUserResponse;
import com.forward.it_software_support_portal.dto.response.LoginResponse;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
import com.forward.it_software_support_portal.security.JwtTokenService;
import com.forward.it_software_support_portal.security.Permission;
import com.forward.it_software_support_portal.security.RolePermissions;
import com.forward.it_software_support_portal.security.ratelimit.ClientIpResolver;
import com.forward.it_software_support_portal.security.ratelimit.LoginAttemptLimiter;
import com.forward.it_software_support_portal.security.ratelimit.TooManyLoginAttemptsException;
import com.forward.it_software_support_portal.security.session.RefreshTokenService;
import com.forward.it_software_support_portal.security.session.RevocationReason;
import com.forward.it_software_support_portal.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication and session lifecycle.
 *
 * <h2>Uniform failures (Phase 4, preserved)</h2>
 *
 * Unknown account, wrong password, no password set and deactivated account all produce a
 * byte-identical 401. Any difference between them - a different message, a different status, even a
 * measurably different response time - is an oracle telling an attacker which addresses are real
 * accounts. That is why a dummy BCrypt verification runs even when no user was found: skipping it would
 * make "no such account" measurably faster than "wrong password".
 *
 * <p>Phase 7 extends the same rule to refresh: unknown token, spent token, revoked token, expired token
 * and deactivated account are one exception and one response.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final ClientIpResolver clientIpResolver;
    private final CurrentUserProvider currentUserProvider;
    private final HttpServletRequest httpServletRequest;

    /**
     * A throwaway hash verified when no account matched, so that the expensive BCrypt comparison
     * happens on every login attempt and "no such user" cannot be told from "wrong password" by timing.
     */
    private final String dummyHash;

    public AuthServiceImpl(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenService jwtTokenService,
                           RefreshTokenService refreshTokenService,
                           LoginAttemptLimiter loginAttemptLimiter,
                           ClientIpResolver clientIpResolver,
                           CurrentUserProvider currentUserProvider,
                           HttpServletRequest httpServletRequest) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptLimiter = loginAttemptLimiter;
        this.clientIpResolver = clientIpResolver;
        this.currentUserProvider = currentUserProvider;
        this.httpServletRequest = httpServletRequest;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /**
     * Writable rather than read-only since Phase 7: a successful login now also writes a refresh-token
     * row, so the token and the session it belongs to are created in one transaction. A login that
     * returned a refresh token which failed to persist would hand the client a credential the server
     * does not recognise.
     */
    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);

        // Before the user lookup and before BCrypt on purpose: a throttled attempt must cost the
        // attacker a round trip and cost this server nothing.
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
     * <strong>Not rate-limited, and that is reasoned rather than overlooked.</strong> Login throttling
     * exists because a password is low-entropy and guessable. A refresh token is 256 bits from a
     * CSPRNG: there is nothing to guess, so a limiter would add state and a lockout vector in exchange
     * for no protection. What protects this endpoint is rotation plus reuse detection - a token works
     * once, and a second use kills the session.
     */
    @Override
    @Transactional
    public LoginResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.RotatedSession rotated =
                refreshTokenService.rotate(request.getRefreshToken());
        return tokenResponse(rotated.user(), rotated.refreshToken());
    }

    @Override
    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.logout(request.getRefreshToken());
    }

    @Override
    @Transactional
    public void logoutAll() {
        User user = requireCurrentUser();
        int revoked = refreshTokenService.revokeAllSessions(user, RevocationReason.LOGOUT_ALL);
        log.info("User {} signed out everywhere: {} session(s) ended and outstanding access tokens "
                + "invalidated", user.getId(), revoked);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser() {
        User user = requireCurrentUser();
        return CurrentUserResponse.builder()
                .id(user.getId())
                .employeeCode(user.getEmployeeCode())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .departmentId(user.getDepartmentId())
                .designationId(user.getDesignationId())
                .role(user.getRole().name())
                .permissions(permissionNames(user))
                .sessionCount(refreshTokenService.liveSessionCount(user.getId()))
                .build();
    }

    /** Sorted so the response is stable between calls and simple to assert on. */
    private static List<String> permissionNames(User user) {
        return RolePermissions.of(user.getRole()).stream()
                .map(Permission::name)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private User requireCurrentUser() {
        Long userId = currentUserProvider.requireCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
    }

    /**
     * Runs a limiter operation without letting it break authentication.
     *
     * <p>A fault inside the limiter is logged at ERROR and swallowed, and the request continues to
     * normal password verification. That is not an authentication bypass - the password is still
     * checked - but it is degraded brute-force protection, so it is reported loudly rather than
     * turning an ordinary login into a 500.
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

        // Always compared, even when there is no account, so every path costs one BCrypt verification.
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

        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issueFor(user);
        log.info("Issued an access token and started a session for user {} with role {}",
                user.getId(), user.getRole());
        return tokenResponse(user, refreshToken);
    }

    private LoginResponse tokenResponse(User user, RefreshTokenService.IssuedRefreshToken refreshToken) {
        JwtTokenService.IssuedToken accessToken = jwtTokenService.issue(user);
        return LoginResponse.builder()
                .accessToken(accessToken.token())
                .tokenType("Bearer")
                .expiresIn(accessToken.expiresIn())
                .refreshToken(refreshToken.rawToken())
                .refreshExpiresIn(refreshToken.expiresInSeconds())
                .userId(user.getId())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .build();
    }

    private static InvalidCredentialsException invalidCredentials() {
        return new InvalidCredentialsException("Invalid email or password");
    }
}
