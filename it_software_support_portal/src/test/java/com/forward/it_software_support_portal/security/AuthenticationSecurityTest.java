package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication: the login endpoint, token handling, and the 401 boundary.
 */
@DatabaseIntegrationTest
class AuthenticationSecurityTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    private long userId;

    @BeforeEach
    void seed() {
        userId = insertUserWithPassword("Auth User", "auth.user@example.test", 8001L,
                "IT_SUPPORT", PASSWORD);
    }

    private Map<String, Object> credentials(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return body;
    }

    // --------------------------------------------------------------- login

    @Test
    @DisplayName("correct credentials return a usable access token")
    void correctCredentialsSucceed() {
        ResponseEntity<Map<String, Object>> response =
                postObject("/api/auth/login", credentials("auth.user@example.test", PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull()
                .containsEntry("tokenType", "Bearer")
                .containsEntry("userId", (int) userId)
                .containsEntry("fullName", "Auth User")
                .containsEntry("role", "IT_SUPPORT");
        assertThat((String) body.get("accessToken")).isNotBlank();
        assertThat(((Number) body.get("expiresIn")).longValue()).isPositive();

        // The token actually works against a protected endpoint.
        assertThat(getObjectWithToken((String) body.get("accessToken"), "/api/tickets/999999")
                .getStatusCode())
                .as("authenticated, so the failure must be 404 rather than 401")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an incorrect password is 401")
    void incorrectPasswordFails() {
        assertThat(postObject("/api/auth/login",
                credentials("auth.user@example.test", "wrong-password")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unknown email is 401, with the same response as a wrong password")
    void unknownEmailFails() {
        ResponseEntity<String> unknown = rest.postForEntity("/api/auth/login",
                credentials("nobody@example.test", PASSWORD), String.class);
        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/auth/login",
                credentials("auth.user@example.test", "wrong-password"), String.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknown.getBody())
                .as("""
                        Identical responses on purpose. A different message or status for "no such user" \
                        would turn login into an account-enumeration oracle.""")
                .isEqualTo(wrongPassword.getBody());
    }

    @Test
    @DisplayName("a user with no password set cannot authenticate")
    void userWithoutPasswordCannotAuthenticate() {
        insertUser("No Password", "nopassword@example.test", 8002L, "ADMIN");

        assertThat(postObject("/api/auth/login",
                credentials("nopassword@example.test", "anything-at-all")).getStatusCode())
                .as("""
                        Every user that existed before Phase 4 has a NULL password hash. Enabling \
                        authentication must not hand them usable accounts.""")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a deactivated user cannot authenticate")
    void deactivatedUserCannotAuthenticate() {
        jdbc.update("UPDATE users SET active = false WHERE id = ?", userId);

        assertThat(postObject("/api/auth/login",
                credentials("auth.user@example.test", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the login response never contains a password or hash")
    void loginResponseLeaksNoCredential() {
        String body = rest.postForEntity("/api/auth/login",
                credentials("auth.user@example.test", PASSWORD), String.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain(PASSWORD);
        assertThat(body.toLowerCase())
                .doesNotContain("passwordhash")
                .doesNotContain("password_hash")
                .doesNotContain("$2a$");
    }

    @Test
    @DisplayName("login validates its input")
    void loginValidatesInput() {
        assertThat(postObject("/api/auth/login", credentials("", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postObject("/api/auth/login", credentials("not-an-email", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(postObject("/api/auth/login",
                credentials("auth.user@example.test", "")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------- token handling

    @Test
    @DisplayName("a garbage token is 401, not 500")
    void garbageTokenIsUnauthorized() {
        assertThat(getObjectWithToken("not-a-token", "/api/tickets").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tamperedTokenIsRejected() {
        String valid = tokenFor(userId);
        // Flip a character in the signature segment.
        String tampered = valid.substring(0, valid.length() - 2)
                + (valid.endsWith("A") ? "B" : "A");

        assertThat(getObjectWithToken(tampered, "/api/tickets").getStatusCode())
                .as("the signature no longer verifies, so the token is worthless")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void foreignlySignedTokenIsRejected() {
        // A structurally valid JWT for the same subject, signed with a key this service does not know.
        String forged = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJpc3MiOiJpdC1zb2Z0d2FyZS1zdXBwb3J0LXBvcnRhbCIsInN1YiI6IjEiLCJyb2xlIjoiQURNSU4ifQ."
                + "ZmFrZS1zaWduYXR1cmUtdGhhdC13aWxsLW5vdC12ZXJpZnk";

        assertThat(getObjectWithToken(forged, "/api/tickets").getStatusCode())
                .as("anyone able to mint accepted tokens would own the system; only our key signs")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("401 responses are RFC 7807 and advertise the scheme without leaking details")
    void unauthorizedResponseShape() {
        ResponseEntity<String> response = rest.getForEntity("/api/tickets", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).contains("Bearer");
        assertThat(response.getBody()).isNotNull()
                .contains("\"status\":401")
                .contains("\"title\"");
        assertThat(response.getBody().toLowerCase())
                .as("no internals in a security response")
                .doesNotContain("exception")
                .doesNotContain("org.springframework")
                .doesNotContain("hs256")
                .doesNotContain("\tat ");
    }

    @Test
    @DisplayName("the login endpoint itself is public")
    void loginEndpointIsPublic() {
        // Reached without credentials: a wrong password returns 401 from the service, not from the
        // filter chain refusing the request outright.
        assertThat(postObject("/api/auth/login",
                credentials("auth.user@example.test", "wrong")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(postObject("/api/auth/login",
                credentials("auth.user@example.test", PASSWORD)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("no session cookie is created - the API is stateless")
    void noSessionIsCreated() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                credentials("auth.user@example.test", PASSWORD), String.class);

        assertThat(response.getHeaders().get("Set-Cookie"))
                .as("""
                        Statelessness is what makes CSRF protection unnecessary here and horizontal \
                        scaling possible. A session cookie would undermine both.""")
                .isNullOrEmpty();
    }
}
