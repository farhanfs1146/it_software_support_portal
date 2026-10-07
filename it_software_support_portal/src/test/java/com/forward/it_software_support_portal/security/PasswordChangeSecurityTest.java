package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PATCH /api/users/me/password} end to end, including what login does afterwards.
 *
 * <p>The scenario that motivated the endpoint is the one asserted first: a bootstrap administrator,
 * created from a password in a deployment script, replaces it and the deployed password stops
 * working. Until this endpoint existed there was no way to do that - the initializer logged "change
 * this password after first sign-in" and the API offered nothing that could.
 *
 * <p>An integration test rather than a unit test because the thing worth proving spans the whole
 * stack: that the new hash is what {@code POST /api/auth/login} subsequently verifies against.
 * {@link com.forward.it_software_support_portal.unit.ChangeOwnPasswordTest} covers the service rules
 * without Docker.
 */
@DatabaseIntegrationTest
class PasswordChangeSecurityTest extends AbstractIntegrationTest {

    private static final String ORIGINAL = "deployed-password-01";
    private static final String REPLACEMENT = "chosen-password-02";

    private long adminId;
    private long employeeId;

    @BeforeEach
    void seed() {
        adminId = insertUserWithPassword("Bootstrap Administrator", "admin.pw@example.test",
                9701L, "ADMIN", ORIGINAL);
        employeeId = insertUserWithPassword("Ordinary Employee", "employee.pw@example.test",
                9702L, "EMPLOYEE", ORIGINAL);
    }

    private org.springframework.http.HttpStatusCode loginStatus(String email, String password) {
        return postObject("/api/auth/login",
                Map.of("email", email, "password", password)).getStatusCode();
    }

    @Test
    @DisplayName("an administrator replaces the deployed password, and the old one stops working")
    void administratorReplacesTheDeployedPassword() {
        assertThat(patchAs(adminId, "/api/users/me/password", Map.of(
                "currentPassword", ORIGINAL,
                "newPassword", REPLACEMENT)).getStatusCode())
                .as("nothing is returned, so 204 rather than an empty 200")
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(loginStatus("admin.pw@example.test", REPLACEMENT))
                .as("the chosen password now authenticates")
                .isEqualTo(HttpStatus.OK);
        assertThat(loginStatus("admin.pw@example.test", ORIGINAL))
                .as("""
                        The point of the endpoint: the password sitting in the deployment \
                        configuration no longer mints tokens.""")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("any authenticated role may change its own password - no permission is required")
    void anyRoleMayChangeItsOwnPassword() {
        assertThat(patchAs(employeeId, "/api/users/me/password", Map.of(
                "currentPassword", ORIGINAL,
                "newPassword", REPLACEMENT)).getStatusCode())
                .as("an EMPLOYEE holds no USER_* permission and must still be able to do this")
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(loginStatus("employee.pw@example.test", REPLACEMENT)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a wrong current password is 401 and changes nothing")
    void wrongCurrentPasswordIsRejected() {
        assertThat(patchAs(adminId, "/api/users/me/password", Map.of(
                "currentPassword", "not-the-password",
                "newPassword", REPLACEMENT)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(loginStatus("admin.pw@example.test", ORIGINAL))
                .as("the original password still works, so nothing was written")
                .isEqualTo(HttpStatus.OK);
        assertThat(loginStatus("admin.pw@example.test", REPLACEMENT)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a new password below the minimum length is 400, with the field named")
    void shortNewPasswordIsRejected() {
        assertThat(patchAs(adminId, "/api/users/me/password", Map.of(
                "currentPassword", ORIGINAL,
                "newPassword", "short")).getStatusCode())
                .as("the same 12-character floor POST /api/users enforces")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(loginStatus("admin.pw@example.test", ORIGINAL)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the endpoint is not reachable without a token")
    void anonymousCallerIsRejected() {
        assertThat(patchAs(null, "/api/users/me/password", Map.of(
                "currentPassword", ORIGINAL,
                "newPassword", REPLACEMENT)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(loginStatus("admin.pw@example.test", ORIGINAL)).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("one user's change does not touch another's password")
    void changeIsScopedToTheCaller() {
        patchAs(adminId, "/api/users/me/password", Map.of(
                "currentPassword", ORIGINAL,
                "newPassword", REPLACEMENT));

        assertThat(loginStatus("employee.pw@example.test", ORIGINAL))
                .as("""
                        There is no {id} in the path - the account comes from the token - so this \
                        cannot reach another user. Asserted rather than assumed, because a path \
                        parameter is the obvious next change someone would make.""")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a refusal never echoes the submitted password")
    void refusalDoesNotEchoThePassword() {
        // The 400 path is the one with something to leak: validation failures report field names and
        // messages, and a ProblemDetail that included the rejected value would put a password in
        // every client log and error tracker that captures response bodies.
        String body = getRawAs(adminId, "/api/users/" + adminId).getBody();
        assertThat(body)
                .as("the user representation carries no password material at all")
                .doesNotContain(ORIGINAL)
                .doesNotContain("passwordHash");

        patchAs(adminId, "/api/users/me/password", Map.of(
                "currentPassword", ORIGINAL,
                "newPassword", "short"));

        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, adminId))
                .as("stored as a BCrypt hash, never as the plaintext")
                .startsWith("$2")
                .doesNotContain(ORIGINAL);
    }
}
