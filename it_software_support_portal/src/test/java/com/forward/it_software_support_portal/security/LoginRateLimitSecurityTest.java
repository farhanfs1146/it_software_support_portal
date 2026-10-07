package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end login abuse protection (Phase 5).
 *
 * <p>Unit-level behaviour of the throttle lives in
 * {@code security/ratelimit/InMemoryLoginAttemptLimiterTest}, which runs without Docker. This class
 * checks the parts only a real HTTP request can show: the status code, the {@code Retry-After} header,
 * the problem document, and that throttling does not leak whether an account exists.
 *
 * <p><strong>All requests here share one client address</strong> — the test client connects over
 * loopback — so the per-address budget (20) is the ceiling for a whole test method. Tests that need to
 * exhaust only the per-account budget (5) stay well inside it.
 */
@DatabaseIntegrationTest
class LoginRateLimitSecurityTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "a-correct-and-long-password";
    private static final String EMAIL = "throttle.target@example.test";

    @BeforeEach
    void seed() {
        insertUserWithPassword("Throttle Target", EMAIL, 8501L, "IT_SUPPORT", PASSWORD);
    }

    private Map<String, Object> credentials(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return body;
    }

    private ResponseEntity<String> attempt(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/auth/login", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(credentials(email, password), headers),
                String.class);
    }

    /** Sends a login attempt with extra headers, for spoofing tests. */
    private ResponseEntity<String> attemptWithHeaders(String email, String password,
                                                     Map<String, String> extraHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        extraHeaders.forEach(headers::set);
        return rest.exchange("/api/auth/login", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(credentials(email, password), headers),
                String.class);
    }

    // --------------------------------------------------- normal authentication

    @Test
    @DisplayName("valid credentials still succeed")
    void validCredentialsSucceed() {
        assertThat(attempt(EMAIL, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("attempts below the account threshold keep returning 401, not 429")
    void belowThresholdReturns401() {
        for (int i = 1; i <= 4; i++) {
            assertThat(attempt(EMAIL, "wrong-password").getStatusCode())
                    .as("attempt %d of 4 is under the limit, so it must be an ordinary 401", i)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ------------------------------------------------------------ throttling

    @Test
    @DisplayName("repeated failures against one account produce 429 with Retry-After")
    void accountThrottleProduces429() {
        for (int i = 0; i < 5; i++) {
            attempt(EMAIL, "wrong-password");
        }

        ResponseEntity<String> throttled = attempt(EMAIL, "wrong-password");

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("RFC 9110 wants a delta in seconds, so a bare positive integer")
                .isNotNull()
                .matches("\\d+");
        assertThat(Integer.parseInt(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)))
                .isPositive()
                .isLessThanOrEqualTo(900);
    }

    @Test
    @DisplayName("the 429 body is RFC 7807 and exposes no limiter state")
    void throttledBodyLeaksNothing() {
        for (int i = 0; i < 6; i++) {
            attempt(EMAIL, "wrong-password");
        }

        ResponseEntity<String> throttled = attempt(EMAIL, "wrong-password");
        String body = throttled.getBody();

        assertThat(body).isNotNull()
                .contains("\"status\":429")
                .contains("\"title\"")
                .contains("\"detail\"");
        // "attempts" itself is fine - the detail says "Too many failed login attempts", which is the
        // generic explanation a client needs. What must not leak is anything specific.
        assertThat(body.toLowerCase())
                .as("""
                        No counters, no thresholds, no indication of which dimension tripped, and nothing \
                        about whether the account exists or the password was close.""")
                .doesNotContain("counter")
                .doesNotContain("threshold")
                .doesNotContain("remaining")
                .doesNotContain("locked")
                .doesNotContain("address")
                .doesNotContain("ip ")
                .doesNotContain(EMAIL.toLowerCase())
                .doesNotContain("exception")
                .doesNotContain("org.springframework");
    }

    @Test
    @DisplayName("a correct password is still refused while the account is throttled")
    void correctPasswordAlsoThrottled() {
        for (int i = 0; i < 5; i++) {
            attempt(EMAIL, "wrong-password");
        }

        assertThat(attempt(EMAIL, PASSWORD).getStatusCode())
                .as("""
                        The check runs before password verification. That is the point: it denies the \
                        attacker the BCrypt work, and it keeps the response independent of the \
                        credentials supplied.""")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // --------------------------------------------------- enumeration resistance

    @Test
    @DisplayName("unknown account, wrong password and disabled account stay indistinguishable")
    void failureModesRemainIndistinguishable() {
        long disabledId = insertUserWithPassword(
                "Disabled User", "disabled@example.test", 8502L, "EMPLOYEE", PASSWORD);
        jdbc.update("UPDATE users SET active = false WHERE id = ?", disabledId);

        ResponseEntity<String> unknown = attempt("no-such-user@example.test", PASSWORD);
        ResponseEntity<String> wrongPassword = attempt(EMAIL, "wrong-password");
        ResponseEntity<String> disabled = attempt("disabled@example.test", PASSWORD);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(wrongPassword.getBody())
                .as("""
                        Three different internal reasons, one indistinguishable response. Phase 4 \
                        established this; Phase 5 must not have broken it by adding a new status code \
                        that appears only in some of these cases.""")
                .isEqualTo(unknown.getBody())
                .isEqualTo(disabled.getBody());
    }

    @Test
    @DisplayName("throttling a nonexistent account behaves identically, so 429 is not an existence oracle")
    void throttlingDoesNotRevealAccountExistence() {
        // Five failures against an address that does not exist as a user.
        for (int i = 0; i < 5; i++) {
            attempt("ghost@example.test", "wrong-password");
        }
        ResponseEntity<String> ghostThrottled = attempt("ghost@example.test", "wrong-password");

        assertThat(ghostThrottled.getStatusCode())
                .as("""
                        If only real accounts were throttled, a 429 would prove an account exists. The \
                        limiter therefore keys on the submitted string, not a resolved user.""")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ------------------------------------------------------------ success reset

    @Test
    @DisplayName("a successful login clears accumulated failures")
    void successClearsFailureState() {
        for (int i = 0; i < 4; i++) {
            assertThat(attempt(EMAIL, "wrong-password").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        assertThat(attempt(EMAIL, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Four more failures would have crossed the threshold had the earlier ones persisted.
        for (int i = 0; i < 4; i++) {
            assertThat(attempt(EMAIL, "wrong-password").getStatusCode())
                    .as("""
                            Stale failures must not punish a legitimate user who mistyped a few times \
                            and then signed in correctly.""")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // --------------------------------------------------------- bypass attempts

    @Test
    @DisplayName("spoofing X-Forwarded-For does not reset the address budget")
    void forwardedHeaderCannotBypassThrottling() {
        // Exhaust the account budget with a different forged address on every request.
        for (int i = 0; i < 5; i++) {
            attemptWithHeaders(EMAIL, "wrong-password",
                    Map.of("X-Forwarded-For", "203.0.113." + i));
        }

        ResponseEntity<String> throttled = attemptWithHeaders(EMAIL, "wrong-password",
                Map.of("X-Forwarded-For", "198.51.100.42"));

        assertThat(throttled.getStatusCode())
                .as("""
                        trust-forwarded-headers is false and no trusted proxy is configured, so the \
                        header is ignored entirely and the real socket address is used. Honouring it \
                        would let any client defeat per-address throttling by varying one string.""")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("sending X-User-Id does not bypass login or confer identity")
    void legacyIdentityHeaderCannotBypassLogin() {
        ResponseEntity<String> response = attemptWithHeaders(EMAIL, "wrong-password",
                Map.of("X-User-Id", "1"));

        assertThat(response.getStatusCode())
                .as("the retired Phase 2 header is inert; it cannot authenticate or skip throttling")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("varying the account does not reset the address budget")
    void varyingAccountCannotBypassAddressThrottling() {
        // 20 failures spread across 20 distinct accounts: no account reaches 5, but the address does.
        for (int i = 0; i < 20; i++) {
            attempt("spray" + i + "@example.test", "wrong-password");
        }

        assertThat(attempt("never-tried@example.test", "wrong-password").getStatusCode())
                .as("""
                        Credential stuffing sprays many accounts rather than hammering one, so the \
                        address dimension is what catches it.""")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("concurrent failures cannot race past the threshold")
    void concurrentAttemptsCannotRacePastTheThreshold() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            Set<Integer> statuses = ConcurrentHashMap.newKeySet();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    statuses.add(attempt(EMAIL, "wrong-password").getStatusCode().value());
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }

            assertThat(statuses)
                    .as("only 401 and 429 are acceptable outcomes - never a 500, never a 200")
                    .isSubsetOf(401, 429);
            assertThat(attempt(EMAIL, PASSWORD).getStatusCode())
                    .as("16 concurrent failures exceed the limit of 5, so the account must end blocked")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------- authenticated traffic intact

    @Test
    @DisplayName("throttling login does not affect authenticated API traffic")
    void authenticatedTrafficIsUnaffected() {
        long actorId = insertUserWithPassword(
                "Busy Agent", "busy@example.test", 8503L, "IT_SUPPORT", PASSWORD);

        // Block the login endpoint for this address.
        for (int i = 0; i < 20; i++) {
            attempt("spray" + i + "@example.test", "wrong-password");
        }
        assertThat(attempt(EMAIL, "wrong-password").getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(getRawAs(actorId, "/api/tickets").getStatusCode())
                .as("""
                        The limiter guards POST /api/auth/login only. A user holding a valid token must \
                        keep working even while someone attacks the login endpoint from the same address.""")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("authorization behaviour is unchanged: 401 unauthenticated, 403 forbidden")
    void authorizationRegressionHolds() {
        long employeeId = insertUser("Emma Employee", "emma.p5@example.test", 8504L, "EMPLOYEE");

        assertThat(getRaw("/api/tickets").getStatusCode())
                .as("no credentials is still 401, not 429")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRawAs(employeeId, "/api/users").getStatusCode())
                .as("authenticated but unpermitted is still 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------- security headers

    @Test
    @DisplayName("security headers are present on an authentication response")
    void securityHeadersArePresent() {
        ResponseEntity<String> response = attempt(EMAIL, PASSWORD);
        HttpHeaders headers = response.getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy"))
                .as("added in Phase 5: stops ids in URLs leaking through the Referer header")
                .isEqualTo("no-referrer");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL))
                .as("""
                        A token response must never be cached by a browser or an intermediary. Spring \
                        Security sets this by default; asserting it means a future header change cannot \
                        silently remove it.""")
                .contains("no-store");
    }

    @Test
    @DisplayName("security headers are present on a throttled response too")
    void securityHeadersPresentOnThrottledResponse() {
        for (int i = 0; i < 6; i++) {
            attempt(EMAIL, "wrong-password");
        }

        HttpHeaders headers = attempt(EMAIL, "wrong-password").getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
