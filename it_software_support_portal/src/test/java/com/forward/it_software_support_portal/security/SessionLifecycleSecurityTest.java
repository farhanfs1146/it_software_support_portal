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
 * The session lifecycle over real HTTP against a real database: login, refresh, rotation, replay
 * detection, logout and sign-out-everywhere.
 *
 * <p>These need a database rather than mocks, because the behaviour under test <em>is</em> database
 * behaviour: the conditional update that decides a concurrent refresh, the unique index on the token
 * hash, the foreign key to {@code users}, and the fact that a 401 reaches the client as the RFC 7807
 * body the API promises. The rules themselves are pinned separately, without Docker, in
 * {@code unit/RefreshTokenRotationTest}.
 */
@DatabaseIntegrationTest
@DisplayName("Phase 7: session lifecycle")
class SessionLifecycleSecurityTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-correct-horse-battery";

    private long userId;

    @BeforeEach
    void createUser() {
        userId = insertUserWithPassword("Dana Dev", "dana@example.test", 910001L,
                "DEVELOPER", PASSWORD);
    }

    // ----------------------------------------------------------------- login

    @Test
    @DisplayName("login returns both an access token and a refresh token")
    void loginReturnsBothTokens() {
        Map<String, Object> body = login("dana@example.test", PASSWORD);

        assertThat(accessTokenOf(body)).isNotBlank();
        assertThat(refreshTokenOf(body))
                .describedAs("without this, a client has to keep the password to stay signed in")
                .isNotBlank();
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat(((Number) body.get("expiresIn")).longValue()).isPositive();
        assertThat(((Number) body.get("refreshExpiresIn")).longValue())
                .describedAs("the refresh token must outlive the access token, or it buys nothing")
                .isGreaterThan(((Number) body.get("expiresIn")).longValue());
    }

    @Test
    @DisplayName("login stores a hash, never the refresh token itself")
    void loginStoresOnlyAHash() {
        String refreshToken = refreshTokenOf(login("dana@example.test", PASSWORD));

        List<String> hashes = jdbc.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, userId);

        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0))
                .describedAs("a database disclosure must not yield usable session credentials")
                .isNotEqualTo(refreshToken)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the refresh token never appears anywhere but the response body")
    void refreshTokenIsNotStoredInPlaintext() {
        String refreshToken = refreshTokenOf(login("dana@example.test", PASSWORD));

        Integer matches = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class,
                refreshToken);

        assertThat(matches).isZero();
    }

    @Test
    @DisplayName("two sign-ins create two independent sessions")
    void twoSignInsAreIndependent() {
        Map<String, Object> first = login("dana@example.test", PASSWORD);
        Map<String, Object> second = login("dana@example.test", PASSWORD);

        assertThat(liveSessionRows(userId)).isEqualTo(2);

        // Logging out of one must not touch the other.
        postObject("/api/auth/logout", Map.of("refreshToken", refreshTokenOf(first)));

        assertThat(refresh(refreshTokenOf(second)).getStatusCode())
                .describedAs("signing out on a phone must not sign the user out on their laptop")
                .isEqualTo(HttpStatus.OK);
    }

    // --------------------------------------------------------------- refresh

    @Test
    @DisplayName("refresh returns a new access token and a new refresh token")
    void refreshReturnsANewPair() {
        Map<String, Object> initial = login("dana@example.test", PASSWORD);

        ResponseEntity<Map<String, Object>> refreshed = refresh(refreshTokenOf(initial));

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshTokenOf(refreshed.getBody()))
                .describedAs("rotation: the token presented is consumed and replaced")
                .isNotBlank()
                .isNotEqualTo(refreshTokenOf(initial));
        assertThat(accessTokenOf(refreshed.getBody())).isNotBlank();
    }

    @Test
    @DisplayName("the access token from a refresh works on protected endpoints")
    void refreshedAccessTokenIsUsable() {
        Map<String, Object> initial = login("dana@example.test", PASSWORD);
        Map<String, Object> refreshed = refresh(refreshTokenOf(initial)).getBody();

        ResponseEntity<Map<String, Object>> me =
                getObjectWithToken(accessTokenOf(refreshed), "/api/auth/me");

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) me.getBody().get("id")).longValue()).isEqualTo(userId);
    }

    @Test
    @DisplayName("refresh needs no access token - that is the point of it")
    void refreshNeedsNoAccessToken() {
        String refreshToken = refreshTokenOf(login("dana@example.test", PASSWORD));

        assertThat(refresh(refreshToken).getStatusCode())
                .describedAs("by the time a client refreshes, its access token has expired")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the rotated-away token is refused on its second use")
    void spentTokenIsRefused() {
        String original = refreshTokenOf(login("dana@example.test", PASSWORD));
        refresh(original);

        assertThat(refresh(original).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unknown refresh token is refused")
    void unknownTokenIsRefused() {
        assertThat(refresh("this-token-was-never-issued-at-all").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a blank refresh token is a 400, not a 401 - it is a malformed request")
    void blankTokenIsABadRequest() {
        ResponseEntity<Map<String, Object>> response =
                postObject("/api/auth/refresh", Map.of("refreshToken", ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a failed refresh says nothing about why it failed")
    void failedRefreshRevealsNothing() {
        String spent = refreshTokenOf(login("dana@example.test", PASSWORD));
        refresh(spent);

        Map<String, Object> afterReuse = refresh(spent).getBody();
        Map<String, Object> afterUnknown = refresh("never-issued-at-all-really").getBody();

        assertThat(afterReuse.get("detail"))
                .describedAs("'expired' proves the token was real, 'already used' proves the victim "
                        + "is active, 'unknown' proves it never was - one body answers none of those")
                .isEqualTo(afterUnknown.get("detail"));
        assertThat(afterReuse.get("title")).isEqualTo(afterUnknown.get("title"));
        assertThat(afterReuse.get("status")).isEqualTo(401);
    }

    @Test
    @DisplayName("a chain of refreshes keeps exactly one live token")
    void refreshChainKeepsOneLiveToken() {
        String token = refreshTokenOf(login("dana@example.test", PASSWORD));
        for (int i = 0; i < 5; i++) {
            token = refreshTokenOf(refresh(token).getBody());
        }

        assertThat(liveSessionRows(userId))
                .describedAs("rotation must not accumulate live tokens")
                .isEqualTo(1);
    }

    // ------------------------------------------------------- reuse detection

    @Test
    @DisplayName("replaying a spent token revokes the successor too, ending the whole session")
    void replayRevokesTheWholeFamily() {
        String first = refreshTokenOf(login("dana@example.test", PASSWORD));
        String second = refreshTokenOf(refresh(first).getBody());

        // The thief replays the token the real client already spent.
        assertThat(refresh(first).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(refresh(second).getStatusCode())
                .describedAs("revoking only the replayed token would leave the successor valid, so "
                        + "whoever rotated it keeps the session - the detection would be pointless")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(liveSessionRows(userId)).isZero();
    }

    @Test
    @DisplayName("reuse detection records why, so an investigation can tell it from a rotation")
    void reuseIsRecordedDistinctly() {
        String first = refreshTokenOf(login("dana@example.test", PASSWORD));
        String second = refreshTokenOf(refresh(first).getBody());
        refresh(first);

        assertThat(revocationReasonsFor(userId)).contains("ROTATED", "REUSE_DETECTED");
        assertThat(second).isNotBlank();
    }

    @Test
    @DisplayName("reuse in one session does not disturb another session")
    void reuseIsScopedToItsOwnFamily() {
        Map<String, Object> laptop = login("dana@example.test", PASSWORD);
        Map<String, Object> phone = login("dana@example.test", PASSWORD);

        String laptopFirst = refreshTokenOf(laptop);
        refresh(laptopFirst);
        refresh(laptopFirst); // replay on the laptop

        assertThat(refresh(refreshTokenOf(phone)).getStatusCode())
                .describedAs("families exist precisely so that one compromised chain does not end "
                        + "every session the user has")
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- logout

    @Test
    @DisplayName("logout ends the session and returns 204")
    void logoutEndsTheSession() {
        String refreshToken = refreshTokenOf(login("dana@example.test", PASSWORD));

        ResponseEntity<Void> response = rest.postForEntity("/api/auth/logout",
                new org.springframework.http.HttpEntity<>(Map.of("refreshToken", refreshToken),
                        jsonHeaders()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(liveSessionRows(userId)).isZero();
    }

    @Test
    @DisplayName("logout with an unknown token is still 204, so it is not a guessing oracle")
    void logoutWithUnknownTokenIsStillSuccessful() {
        ResponseEntity<Void> response = rest.postForEntity("/api/auth/logout",
                new org.springframework.http.HttpEntity<>(
                        Map.of("refreshToken", "never-issued-anywhere"), jsonHeaders()), Void.class);

        assertThat(response.getStatusCode())
                .describedAs("a 404 here would confirm which guessed tokens are real")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("logging out twice is idempotent")
    void logoutIsIdempotent() {
        String refreshToken = refreshTokenOf(login("dana@example.test", PASSWORD));

        postObject("/api/auth/logout", Map.of("refreshToken", refreshToken));
        ResponseEntity<Void> second = rest.postForEntity("/api/auth/logout",
                new org.springframework.http.HttpEntity<>(Map.of("refreshToken", refreshToken),
                        jsonHeaders()), Void.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("logout leaves the access token alive until it expires - logout-all is for that")
    void logoutDoesNotKillTheAccessToken() {
        Map<String, Object> session = login("dana@example.test", PASSWORD);

        postObject("/api/auth/logout", Map.of("refreshToken", refreshTokenOf(session)));

        assertThat(getObjectWithToken(accessTokenOf(session), "/api/auth/me").getStatusCode())
                .describedAs("documented and deliberate: ending one session does not revoke the "
                        + "account's tokens, which is what logout-all does")
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------ logout-all

    @Test
    @DisplayName("logout-all kills the access token immediately, not when it expires")
    void logoutAllKillsTheAccessTokenAtOnce() {
        Map<String, Object> session = login("dana@example.test", PASSWORD);
        String accessToken = accessTokenOf(session);
        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        postWithToken(accessToken, "/api/auth/logout-all", null);

        assertThat(getObjectWithToken(accessToken, "/api/auth/me").getStatusCode())
                .describedAs("this is the whole point of Phase 7: a signed, unexpired token that no "
                        + "longer works")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("logout-all ends every session, on every device")
    void logoutAllEndsEverySession() {
        Map<String, Object> laptop = login("dana@example.test", PASSWORD);
        Map<String, Object> phone = login("dana@example.test", PASSWORD);

        postWithToken(accessTokenOf(laptop), "/api/auth/logout-all", null);

        assertThat(liveSessionRows(userId)).isZero();
        assertThat(refresh(refreshTokenOf(phone)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("logout-all bumps the token version, which is the revocation mechanism")
    void logoutAllBumpsTheTokenVersion() {
        Map<String, Object> session = login("dana@example.test", PASSWORD);
        int before = tokenVersionOf(userId);

        postWithToken(accessTokenOf(session), "/api/auth/logout-all", null);

        assertThat(tokenVersionOf(userId)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("logout-all requires an access token")
    void logoutAllRequiresAuthentication() {
        ResponseEntity<Map<String, Object>> response =
                postObject("/api/auth/logout-all", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a fresh login works immediately after logout-all")
    void loginWorksAgainAfterLogoutAll() {
        Map<String, Object> session = login("dana@example.test", PASSWORD);
        postWithToken(accessTokenOf(session), "/api/auth/logout-all", null);

        Map<String, Object> again = login("dana@example.test", PASSWORD);

        assertThat(getObjectWithToken(accessTokenOf(again), "/api/auth/me").getStatusCode())
                .describedAs("revocation must end sessions, not disable the account")
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------- /me

    @Test
    @DisplayName("/me reports the caller's identity and the permissions the server will enforce")
    void meReportsIdentityAndPermissions() {
        Map<String, Object> session = login("dana@example.test", PASSWORD);

        Map<String, Object> me = getObjectWithToken(accessTokenOf(session), "/api/auth/me").getBody();

        assertThat(((Number) me.get("id")).longValue()).isEqualTo(userId);
        assertThat(me.get("email")).isEqualTo("dana@example.test");
        assertThat(me.get("role")).isEqualTo("DEVELOPER");
        assertThat(permissionsOf(me))
                .describedAs("the list a client can render against, taken from the same table "
                        + "@PreAuthorize consults")
                .containsExactly("APPLICATION_READ", "TICKET_ASSIGN", "TICKET_CREATE",
                        "TICKET_READ_ALL", "TICKET_READ_OWN", "TICKET_STATUS_CHANGE", "USER_READ");
    }

    @Test
    @DisplayName("/me never exposes a password hash or a token")
    void meExposesNoSecrets() {
        Map<String, Object> session = login("dana@example.test", PASSWORD);

        Map<String, Object> me = getObjectWithToken(accessTokenOf(session), "/api/auth/me").getBody();

        assertThat(me).doesNotContainKeys("passwordHash", "password", "tokenVersion",
                "accessToken", "refreshToken");
    }

    @Test
    @DisplayName("/me counts the caller's live sessions")
    void meCountsLiveSessions() {
        login("dana@example.test", PASSWORD);
        Map<String, Object> second = login("dana@example.test", PASSWORD);

        Map<String, Object> me = getObjectWithToken(accessTokenOf(second), "/api/auth/me").getBody();

        assertThat(((Number) me.get("sessionCount")).longValue())
                .describedAs("shown so a user can notice a session they do not recognise")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("/me requires an access token")
    void meRequiresAuthentication() {
        assertThat(getObject("/api/auth/me").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --------------------------------------------------------- account state

    @Test
    @DisplayName("a deactivated account cannot refresh, even with a token issued while it was active")
    void deactivatedAccountCannotRefresh() {
        String refreshToken = refreshTokenOf(login("dana@example.test", PASSWORD));
        jdbc.update("UPDATE users SET active = false WHERE id = ?", userId);

        assertThat(refresh(refreshToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("responses carrying tokens are not cacheable")
    void tokenResponsesAreNotCacheable() {
        ResponseEntity<Map<String, Object>> response = postObject("/api/auth/login",
                Map.of("email", "dana@example.test", "password", PASSWORD));

        assertThat(response.getHeaders().getCacheControl())
                .describedAs("a token in a shared cache is a token handed to the next visitor")
                .contains("no-store");
    }

    @SuppressWarnings("unchecked")
    private static List<String> permissionsOf(Map<String, Object> me) {
        return (List<String>) me.get("permissions");
    }

    private static org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}
