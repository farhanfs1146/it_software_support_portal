package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
import com.forward.it_software_support_portal.security.JwtTokenService;
import com.forward.it_software_support_portal.security.SecurityProperties;
import com.forward.it_software_support_portal.security.ratelimit.ClientIpResolver;
import com.forward.it_software_support_portal.security.ratelimit.LoginAttemptLimiter;
import com.forward.it_software_support_portal.service.impl.AuthServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins down the fallback hash that login verifies against when the account does not exist.
 *
 * <p><strong>What broke, and why a returned value could not catch it.</strong> The fallback used to be
 * a hand-written literal, {@code "$2a$10$ZZZ...Z"}, one character longer than a BCrypt hash may be.
 * {@link BCryptPasswordEncoder#matches} screens the stored hash against a format pattern <em>before</em>
 * hashing anything, so a malformed value made it log a warning and return {@code false} immediately.
 * Login still failed, which is the correct outcome and exactly why every existing test passed - but it
 * failed in about a millisecond instead of the ~80 ms a real verification costs. That difference is an
 * account-enumeration oracle: an attacker times the response and learns which addresses exist, which is
 * the one thing the uniform 401 exists to hide.
 *
 * <p>So this test asserts the property that matters rather than the outcome: whatever login verifies
 * against for an unknown account must be something BCrypt will actually do the work on, at the same
 * cost as a real account's hash.
 *
 * <p>Deliberately a plain unit test - no database, no Docker, no Spring context.
 */
class AuthServiceDummyHashTest {

    /** The pattern {@code BCryptPasswordEncoder} itself applies: 53 characters after the cost field. */
    private static final String BCRYPT_FORMAT = "\\A\\$2[aby]?\\$\\d\\d\\$[./0-9A-Za-z]{53}\\z";

    /** A real encoder that also records every hash it was asked to verify against. */
    private static final class RecordingEncoder implements PasswordEncoder {

        private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();
        private final List<String> verifiedAgainst = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            verifiedAgainst.add(encodedPassword);
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return delegate.upgradeEncoding(encodedPassword);
        }
    }

    private final RecordingEncoder encoder = new RecordingEncoder();
    private final UserRepository userRepository = mock(UserRepository.class);

    private AuthServiceImpl authService() {
        SecurityProperties properties = new SecurityProperties(null, null, null, null, null, false);
        return new AuthServiceImpl(
                userRepository,
                encoder,
                mock(JwtTokenService.class),
                mock(com.forward.it_software_support_portal.security.session.RefreshTokenService.class),
                mock(LoginAttemptLimiter.class),
                new ClientIpResolver(properties),
                mock(com.forward.it_software_support_portal.common.identity.CurrentUserProvider.class),
                mock(HttpServletRequest.class));
    }

    private void attemptLoginForUnknownAccount() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest();
        request.setEmail("nobody@example.com");
        request.setPassword("whatever-was-typed");

        assertThatThrownBy(() -> authService().login(request))
                .as("an unknown account must still be refused")
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("an unknown account is verified against a well-formed BCrypt hash, so BCrypt actually runs")
    void unknownAccountIsVerifiedAgainstARealBcryptHash() {
        attemptLoginForUnknownAccount();

        assertThat(encoder.verifiedAgainst)
                .as("login must perform a password verification even when no account exists")
                .hasSize(1);

        assertThat(encoder.verifiedAgainst.getFirst())
                .as("""
                        BCryptPasswordEncoder rejects a malformed hash on a regex before it hashes \
                        anything, so a fallback that fails this pattern silently skips the work and \
                        reintroduces the timing oracle.""")
                .matches(BCRYPT_FORMAT);
    }

    @Test
    @DisplayName("the fallback hash carries the encoder's own cost, so its timing tracks a real verification")
    void fallbackHashCostMatchesTheEncoder() {
        attemptLoginForUnknownAccount();

        assertThat(encoder.upgradeEncoding(encoder.verifiedAgainst.getFirst()))
                .as("""
                        upgradeEncoding reports true when a hash's cost factor is below the encoder's \
                        configured strength. A fallback pinned at a literal cost would drift the moment \
                        the encoder's strength is raised - quietly, and in the same direction as the \
                        original defect - so it is derived from the encoder instead.""")
                .isFalse();
    }
}
