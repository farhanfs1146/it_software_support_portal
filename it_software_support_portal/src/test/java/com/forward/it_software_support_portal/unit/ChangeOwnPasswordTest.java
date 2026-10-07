package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.dto.request.ChangePasswordRequest;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
import com.forward.it_software_support_portal.service.impl.UserServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code PATCH /api/users/me/password} - the endpoint that lets an account replace its own password.
 *
 * <p>It exists to close a contradiction: {@code BootstrapAdminInitializer} told operators to change
 * the bootstrap password after first sign-in, and nothing in the API could do it, so the only
 * administrator a fresh deployment has was stuck on a password that had been typed into a deployment
 * script.
 *
 * <p>A real {@link BCryptPasswordEncoder} is used rather than a mock, because the whole point of these
 * tests is what happens to a hash - a stubbed {@code matches} would assert the stub.
 */
class ChangeOwnPasswordTest {

    private static final long CALLER_ID = 5L;
    private static final String CURRENT = "current-password-01";
    private static final String REPLACEMENT = "replacement-password-02";

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

    private final UserServiceImpl userService =
            new UserServiceImpl(userRepository, passwordEncoder, currentUserProvider,
                    mock(com.forward.it_software_support_portal.security.session.RefreshTokenService.class));

    private User caller(String storedPassword) {
        User user = new User();
        user.setId(CALLER_ID);
        user.setFullName("Bootstrap Administrator");
        user.setRole(Role.ADMIN);
        user.setActive(true);
        user.setPasswordHash(storedPassword == null ? null : passwordEncoder.encode(storedPassword));

        when(currentUserProvider.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(userRepository.findById(CALLER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    private static ChangePasswordRequest request(String current, String replacement) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(current);
        request.setNewPassword(replacement);
        return request;
    }

    @Test
    @DisplayName("the correct current password replaces the hash, and the old one stops working")
    void correctCurrentPasswordReplacesTheHash() {
        User user = caller(CURRENT);
        String originalHash = user.getPasswordHash();

        userService.changeOwnPassword(request(CURRENT, REPLACEMENT));

        assertThat(user.getPasswordHash())
                .as("a new BCrypt hash, not the plaintext and not the old hash")
                .isNotEqualTo(originalHash)
                .doesNotContain(REPLACEMENT);
        assertThat(passwordEncoder.matches(REPLACEMENT, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(CURRENT, user.getPasswordHash()))
                .as("the replaced password must not still authenticate")
                .isFalse();
    }

    @Test
    @DisplayName("a wrong current password is refused and the stored hash is untouched")
    void wrongCurrentPasswordIsRefused() {
        User user = caller(CURRENT);
        String originalHash = user.getPasswordHash();

        assertThatThrownBy(() -> userService.changeOwnPassword(request("not-the-password", REPLACEMENT)))
                .as("""
                        Verifying the current password is what stops a stolen access token becoming \
                        permanent account takeover: the token expires, a changed password does not.""")
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getPasswordHash()).isEqualTo(originalHash);
    }

    @Test
    @DisplayName("an account with no password set cannot claim one through this endpoint")
    void passwordlessAccountIsRefused() {
        User user = caller(null);

        assertThatThrownBy(() -> userService.changeOwnPassword(request("anything", REPLACEMENT)))
                .as("""
                        A NULL hash means "cannot authenticate" (V13). Treating it as "no current \
                        password required" would let any token holder claim every pre-existing \
                        passwordless account.""")
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getPasswordHash()).isNull();
    }

    @Test
    @DisplayName("the refusals are indistinguishable from each other")
    void refusalsRevealNothing() {
        caller(CURRENT);
        String wrongPassword = catchMessage(() -> userService.changeOwnPassword(
                request("not-the-password", REPLACEMENT)));

        caller(null);
        String noPassword = catchMessage(() -> userService.changeOwnPassword(
                request("anything", REPLACEMENT)));

        assertThat(wrongPassword)
                .as("""
                        Same message for a wrong password and for an account that has none, matching \
                        how login refuses. A distinguishable response here would report the state of \
                        an account's credentials.""")
                .isEqualTo(noPassword);
    }

    @Test
    @DisplayName("re-using the same password is allowed, and still re-hashes")
    void reusingTheSamePasswordIsAllowed() {
        User user = caller(CURRENT);
        String originalHash = user.getPasswordHash();

        assertThatCode(() -> userService.changeOwnPassword(request(CURRENT, CURRENT)))
                .as("""
                        No history is stored, so "must differ from the last N" cannot be enforced \
                        honestly, and refusing only the current one buys nothing. Documents the \
                        choice rather than leaving it to be rediscovered.""")
                .doesNotThrowAnyException();

        assertThat(user.getPasswordHash())
                .as("BCrypt salts per call, so even an identical password yields a different hash")
                .isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches(CURRENT, user.getPasswordHash())).isTrue();
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected the change to be refused");
        } catch (InvalidCredentialsException e) {
            return e.getMessage();
        }
    }
}
