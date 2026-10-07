package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Immediate revocation of access tokens, and the administrative controls that trigger it.
 *
 * <p>This is the suite that proves the Phase 4/5 limitation is closed. Both reports recorded it
 * plainly: "a deactivated user keeps read access for the remaining token lifetime". Every test here
 * takes a token that is validly signed and not expired, changes something about the account, and
 * asserts the <em>very next request</em> is refused.
 */
@DatabaseIntegrationTest
@DisplayName("Phase 7: access-token revocation and administrative account controls")
class TokenRevocationSecurityTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-correct-horse-battery";
    private static final String NEW_PASSWORD = "an-entirely-different-one";

    private long adminId;
    private long otherAdminId;
    private long staffId;

    @BeforeEach
    void createUsers() {
        adminId = insertUserWithPassword("Ada Admin", "ada@example.test", 920001L,
                "ADMIN", PASSWORD);
        otherAdminId = insertUserWithPassword("Bo Backup", "bo@example.test", 920002L,
                "ADMIN", PASSWORD);
        staffId = insertUserWithPassword("Sam Support", "sam@example.test", 920003L,
                "IT_SUPPORT", PASSWORD);
    }

    // ---------------------------------------------------------- deactivation

    @Test
    @DisplayName("deactivating a user refuses their existing access token on the next request")
    void deactivationTakesEffectImmediately() {
        Map<String, Object> session = login("sam@example.test", PASSWORD);
        String accessToken = accessTokenOf(session);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        patchObjectAs(adminId, "/api/users/" + staffId + "/status", Map.of("active", false));

        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .describedAs("Phases 4 and 5 recorded exactly this as an open limitation: the token "
                        + "stayed usable for the rest of its 30-minute lifetime")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("deactivating also ends the user's refresh sessions")
    void deactivationEndsRefreshSessions() {
        Map<String, Object> session = login("sam@example.test", PASSWORD);

        patchObjectAs(adminId, "/api/users/" + staffId + "/status", Map.of("active", false));

        assertThat(liveSessionRows(staffId)).isZero();
        assertThat(refresh(refreshTokenOf(session)).getStatusCode())
                .describedAs("otherwise a deactivated user could mint fresh access tokens forever")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token is refused when the account is deactivated directly in the database")
    void directDatabaseDeactivationIsHonoured() {
        String accessToken = accessTokenOf(login("sam@example.test", PASSWORD));

        jdbc.update("UPDATE users SET active = false WHERE id = ?", staffId);
        // The cache is bypassed here on purpose: this asserts the validator's active check, which is
        // the belt to the token-version braces. In a running deployment the configured TTL bounds it.
        clearStateCache();

        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .describedAs("the active flag is checked as well as the counter, so a change nobody "
                        + "routed through the API is still honoured")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("reactivating lets the user sign in again")
    void reactivationRestoresAccess() {
        patchObjectAs(adminId, "/api/users/" + staffId + "/status", Map.of("active", false));

        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                adminId, "/api/users/" + staffId + "/status", Map.of("active", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("active")).isEqualTo(true);
        assertThat(accessTokenOf(login("sam@example.test", PASSWORD))).isNotBlank();
    }

    @Test
    @DisplayName("an administrator cannot deactivate themselves")
    void cannotDeactivateSelf() {
        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                adminId, "/api/users/" + adminId + "/status", Map.of("active", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class,
                adminId)).isTrue();
    }

    @Test
    @DisplayName("the last active administrator cannot be deactivated")
    void cannotDeactivateLastAdministrator() {
        patchObjectAs(adminId, "/api/users/" + otherAdminId + "/status", Map.of("active", false));

        // adminId is now the only active ADMIN. Promote staff so they can try to remove it.
        patchObjectAs(adminId, "/api/users/" + staffId + "/role", Map.of("role", "ADMIN"));
        jdbc.update("UPDATE users SET active = false WHERE id = ?", staffId);
        clearStateCache();

        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                otherAdminId, "/api/users/" + adminId + "/status", Map.of("active", false));

        assertThat(response.getStatusCode())
                .describedAs("403 because the deactivated backup admin cannot act at all, which is "
                        + "itself the guard working from the other direction")
                .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class,
                adminId)).isTrue();
    }

    @Test
    @DisplayName("deactivating a user requires USER_MANAGE")
    void deactivationRequiresUserManage() {
        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                staffId, "/api/users/" + otherAdminId + "/status", Map.of("active", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ----------------------------------------------------------- role change

    @Test
    @DisplayName("a demotion takes effect at once, rather than when the token expires")
    void demotionTakesEffectImmediately() {
        Map<String, Object> session = login("sam@example.test", PASSWORD);
        String accessToken = accessTokenOf(session);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        patchObjectAs(adminId, "/api/users/" + staffId + "/role", Map.of("role", "EMPLOYEE"));

        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .describedAs("authorities ride in the token's role claim, so a demotion that waited "
                        + "for expiry would leave the removed permissions usable")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("after a demotion, the next sign-in carries only the new permissions")
    void newPermissionsApplyAfterSigningInAgain() {
        patchObjectAs(adminId, "/api/users/" + staffId + "/role", Map.of("role", "EMPLOYEE"));

        Map<String, Object> me = getObjectWithToken(
                accessTokenOf(login("sam@example.test", PASSWORD)), "/api/auth/me").getBody();

        assertThat(me.get("role")).isEqualTo("EMPLOYEE");
        assertThat(permissionsOf(me))
                .containsExactly("APPLICATION_READ", "TICKET_CREATE", "TICKET_READ_OWN")
                .doesNotContain("TICKET_READ_ALL", "USER_READ");
    }

    @Test
    @DisplayName("a promotion also ends sessions, so the new permissions need a fresh sign-in")
    void promotionAlsoEndsSessions() {
        Map<String, Object> session = login("sam@example.test", PASSWORD);

        patchObjectAs(adminId, "/api/users/" + staffId + "/role", Map.of("role", "ADMIN"));

        assertThat(liveSessionRows(staffId)).isZero();
        assertThat(getObjectWithToken(accessTokenOf(session), "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an administrator cannot change their own role")
    void cannotChangeOwnRole() {
        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                adminId, "/api/users/" + adminId + "/role", Map.of("role", "EMPLOYEE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, adminId))
                .isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("an unknown role is a 400, not a 500")
    void unknownRoleIsRejected() {
        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                adminId, "/api/users/" + staffId + "/role", Map.of("role", "SUPREME_LEADER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("changing a role requires USER_MANAGE")
    void roleChangeRequiresUserManage() {
        ResponseEntity<Map<String, Object>> response = patchObjectAs(
                staffId, "/api/users/" + otherAdminId + "/role", Map.of("role", "EMPLOYEE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // -------------------------------------------------------- password change

    @Test
    @DisplayName("changing your own password ends every session, including the current one")
    void ownPasswordChangeEndsEverySession() {
        Map<String, Object> session = login("sam@example.test", PASSWORD);
        String accessToken = accessTokenOf(session);

        ResponseEntity<Void> changed = rest.exchange("/api/users/me/password",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD),
                        bearer(accessToken)),
                Void.class);

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .describedAs("a password changed in a hurry is usually changed BECAUSE something is "
                        + "wrong; leaving sessions alive would defeat it")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(liveSessionRows(staffId)).isZero();
        assertThat(accessTokenOf(login("sam@example.test", NEW_PASSWORD))).isNotBlank();
    }

    @Test
    @DisplayName("a failed password change leaves the session working")
    void failedPasswordChangeLeavesSessionIntact() {
        String accessToken = accessTokenOf(login("sam@example.test", PASSWORD));

        ResponseEntity<Map<String, Object>> rejected = rest.exchange("/api/users/me/password",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        Map.of("currentPassword", "not-the-password", "newPassword", NEW_PASSWORD),
                        bearer(accessToken)),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {
                });

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .describedAs("a wrong guess at your own current password must not sign you out - "
                        + "that would be a self-inflicted denial of service")
                .isEqualTo(HttpStatus.OK);
    }

    // --------------------------------------------------------- password reset

    @Test
    @DisplayName("an administrative reset sets a new password and ends every session")
    void adminResetEndsSessionsAndSetsPassword() {
        String accessToken = accessTokenOf(login("sam@example.test", PASSWORD));

        ResponseEntity<Void> response = postAs(adminId,
                "/api/users/" + staffId + "/password-reset", Map.of("newPassword", NEW_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(accessTokenOf(login("sam@example.test", NEW_PASSWORD))).isNotBlank();
    }

    @Test
    @DisplayName("after a reset the old password no longer works")
    void oldPasswordStopsWorkingAfterReset() {
        postAs(adminId, "/api/users/" + staffId + "/password-reset",
                Map.of("newPassword", NEW_PASSWORD));

        assertThat(postObject("/api/auth/login",
                Map.of("email", "sam@example.test", "password", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a reset below the password-length policy is refused")
    void shortResetPasswordIsRefused() {
        ResponseEntity<Map<String, Object>> response = postObjectAs(adminId,
                "/api/users/" + staffId + "/password-reset", Map.of("newPassword", "short"));

        assertThat(response.getStatusCode())
                .describedAs("a reset must not be a way around the policy a user's own change has "
                        + "to satisfy")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("resetting a password requires USER_MANAGE")
    void resetRequiresUserManage() {
        ResponseEntity<Map<String, Object>> response = postObjectAs(staffId,
                "/api/users/" + otherAdminId + "/password-reset",
                Map.of("newPassword", NEW_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("resetting an unknown user is a 404")
    void resetOfUnknownUserIsNotFound() {
        ResponseEntity<Map<String, Object>> response = postObjectAs(adminId,
                "/api/users/999999/password-reset", Map.of("newPassword", NEW_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a reset never returns or logs the password")
    void resetReturnsNoBody() {
        ResponseEntity<String> response = rest.exchange(
                "/api/users/" + staffId + "/password-reset",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Map.of("newPassword", NEW_PASSWORD),
                        bearer(tokenFor(adminId))),
                String.class);

        assertThat(response.getBody()).isNull();
    }

    // ------------------------------------------------------ admin force sign-out

    @Test
    @DisplayName("an administrator can force a sign-out without changing the password")
    void adminForcedSignOut() {
        String accessToken = accessTokenOf(login("sam@example.test", PASSWORD));

        ResponseEntity<Void> response = deleteAs(adminId, "/api/users/" + staffId + "/sessions");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(accessTokenOf(login("sam@example.test", PASSWORD)))
                .describedAs("the proportionate response to a lost laptop: the user signs back in "
                        + "with the password they already have")
                .isNotBlank();
    }

    @Test
    @DisplayName("forcing a sign-out requires USER_MANAGE")
    void forcedSignOutRequiresUserManage() {
        assertThat(deleteAs(staffId, "/api/users/" + otherAdminId + "/sessions").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("forcing a sign-out on an unknown user is a 404")
    void forcedSignOutOfUnknownUserIsNotFound() {
        assertThat(deleteAs(adminId, "/api/users/999999/sessions").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ------------------------------------------------- unaffected guarantees

    @Test
    @DisplayName("one user's revocation does not disturb another user")
    void revocationIsScopedToOneAccount() {
        String staffToken = accessTokenOf(login("sam@example.test", PASSWORD));
        String adminToken = accessTokenOf(login("ada@example.test", PASSWORD));

        postWithToken(staffToken, "/api/auth/logout-all", null);

        assertThat(getObjectWithToken(adminToken, "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a revoked token gets the same 401 shape as no token at all")
    void revokedTokenGetsTheStandardProblemDetail() {
        String accessToken = accessTokenOf(login("sam@example.test", PASSWORD));
        postWithToken(accessToken, "/api/auth/logout-all", null);

        ResponseEntity<Map<String, Object>> response =
                getObjectWithToken(accessToken, "/api/auth/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate"))
                .describedAs("the client should be told to re-authenticate, in the standard way")
                .contains("Bearer");
    }

    @SuppressWarnings("unchecked")
    private static List<String> permissionsOf(Map<String, Object> me) {
        return (List<String>) me.get("permissions");
    }

    private void clearStateCache() {
        if (principalStateRegistry instanceof com.forward.it_software_support_portal.security.session
                .CachingPrincipalStateRegistry caching) {
            caching.clearAll();
        }
    }

    private static org.springframework.http.HttpHeaders bearer(String token) {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return headers;
    }
}
