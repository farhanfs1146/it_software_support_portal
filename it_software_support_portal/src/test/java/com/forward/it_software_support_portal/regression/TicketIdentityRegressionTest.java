package com.forward.it_software_support_portal.regression;

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
 * Audit finding P0-3: the acting user was hardcoded. Fixed in Phase 2, hardened in Phase 4.
 *
 * <p>The history of this one defect is the history of the identity abstraction:
 *
 * <ol>
 *   <li><strong>Originally</strong> {@code createTicket} called {@code userRepository.findById(1L)}, so
 *       every ticket was attributed to whoever held id 1.
 *   <li><strong>Phase 2</strong> introduced {@code CurrentUserProvider} with an implementation that read
 *       an {@code X-User-Id} header. Honest and fail-closed, but it trusted a client-supplied value, so
 *       any caller could act as any user.
 *   <li><strong>Phase 4</strong> replaced that implementation with one reading the authenticated
 *       principal from a verified token. No service changed.
 * </ol>
 *
 * <p>These tests now assert the strong property rather than the transitional one: identity comes from a
 * credential the server verified, and nothing a client can set influences it.
 */
@DatabaseIntegrationTest
class TicketIdentityRegressionTest extends AbstractIntegrationTest {

    private long firstUserId;
    private long secondUserId;
    private long applicationId;

    @BeforeEach
    void seed() {
        firstUserId = insertUser("First User", "first.identity@example.test", 9701L, "EMPLOYEE");
        secondUserId = insertUser("Second User", "second.identity@example.test", 9702L, "EMPLOYEE");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> ticketBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Identity probe");
        body.put("description", "Who is recorded as the raiser?");
        body.put("issueType", "BUG");
        body.put("priority", "LOW");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    private long raisedByOf(long ticketId) {
        return jdbc.queryForObject("SELECT raised_by FROM tickets WHERE id = ?", Long.class, ticketId);
    }

    @Test
    @DisplayName("FIXED (P0-3): the raiser is the authenticated caller, not user id 1")
    void raiserIsTheAuthenticatedCaller() {
        assertThat(firstUserId)
                .as("user id 1 exists, so a regression to the hardcoded id would be invisible without it")
                .isEqualTo(1L);

        ResponseEntity<Map<String, Object>> response =
                postObjectAs(secondUserId, "/api/tickets", ticketBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("the authenticated caller was user %d, not user 1", secondUserId)
                .containsEntry("raisedBy", "Second User");

        long ticketId = ((Number) response.getBody().get("id")).longValue();
        assertThat(raisedByOf(ticketId)).isEqualTo(secondUserId).isNotEqualTo(firstUserId);
    }

    @Test
    @DisplayName("FIXED (P0-3): different authenticated callers are attributed differently")
    void differentCallersAreAttributedDifferently() {
        long first = ((Number) postObjectAs(firstUserId, "/api/tickets", ticketBody())
                .getBody().get("id")).longValue();
        long second = ((Number) postObjectAs(secondUserId, "/api/tickets", ticketBody())
                .getBody().get("id")).longValue();

        assertThat(raisedByOf(first)).isEqualTo(firstUserId);
        assertThat(raisedByOf(second)).isEqualTo(secondUserId);
    }

    @Test
    @DisplayName("FIXED (P0-3): creation works when no user has id 1")
    void creationWorksWithoutUserOne() {
        jdbc.execute("TRUNCATE TABLE ticket_history_tracking, tickets, users RESTART IDENTITY CASCADE");
        long appId = insertApplication("Payroll", "Salary");
        jdbc.update("ALTER SEQUENCE users_id_seq RESTART WITH 50");
        long onlyUser = insertUser("Only User", "only@example.test", 9777L, "EMPLOYEE");
        assertThat(onlyUser).isNotEqualTo(1L);

        Map<String, Object> body = ticketBody();
        body.put("applicationId", appId);

        ResponseEntity<Map<String, Object>> response = postObjectAs(onlyUser, "/api/tickets", body);

        assertThat(response.getStatusCode())
                .as("""
                        Before Phase 2 this failed with "Raised by user not found" - which is why ticket \
                        creation was broken against the empty development database.""")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("raisedBy", "Only User");
    }

    @Test
    @DisplayName("an unauthenticated write is 401, never defaulted to a user")
    void unauthenticatedWriteIsUnauthorized() {
        ResponseEntity<Map<String, Object>> response = postObject("/api/tickets", ticketBody());

        assertThat(response.getStatusCode())
                .as("""
                        401 now, where Phase 2 returned 400. Phase 2 was right at the time - advertising \
                        401 without an authentication scheme would have implied one that did not exist.""")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countRows("tickets")).isZero();
    }

    @Test
    @DisplayName("the retired X-User-Id header cannot influence or override identity")
    void legacyHeaderCannotOverrideIdentity() {
        // The exact mechanism Phase 2 used, now sent alongside a genuine token for a different user.
        ResponseEntity<Map<String, Object>> response = postObjectAsWithExtraHeader(
                secondUserId, "/api/tickets", ticketBody(), "X-User-Id", String.valueOf(firstUserId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("""
                        The header is no longer read anywhere in production code - the provider that \
                        read it was deleted, not merely bypassed. Identity comes from the verified \
                        token, so the header is inert.""")
                .containsEntry("raisedBy", "Second User");

        long ticketId = ((Number) response.getBody().get("id")).longValue();
        assertThat(raisedByOf(ticketId)).isEqualTo(secondUserId).isNotEqualTo(firstUserId);
    }

    @Test
    @DisplayName("the X-User-Id header alone grants nothing")
    void legacyHeaderAloneIsRejected() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(firstUserId));

        ResponseEntity<String> response = rest.exchange(
                "/api/tickets", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(ticketBody(), headers), String.class);

        assertThat(response.getStatusCode())
                .as("what used to be a sufficient identity is now simply an unauthenticated request")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(countRows("tickets")).isZero();
    }

    @Test
    @DisplayName("reads also require authentication")
    void readsRequireAuthentication() {
        long ticketId = ((Number) postObjectAs(firstUserId, "/api/tickets", ticketBody())
                .getBody().get("id")).longValue();

        assertThat(getRaw("/api/tickets/" + ticketId).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRaw("/api/tickets").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRaw("/api/users").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an authenticated caller can read their own ticket")
    void authenticatedCallerReadsOwnTicket() {
        long ticketId = ((Number) postObjectAs(firstUserId, "/api/tickets", ticketBody())
                .getBody().get("id")).longValue();

        assertThat(getObjectAs(firstUserId, "/api/tickets/" + ticketId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getArrayAs(firstUserId, "/api/tickets").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
