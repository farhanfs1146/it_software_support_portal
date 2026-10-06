package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resource-level authorization: IDOR / BOLA.
 *
 * <p>The requirement being tested is blunt: <strong>a user must not reach another user's resource by
 * changing an id in the URL.</strong> Permission checks on an endpoint cannot provide this, because
 * every requester legitimately holds {@code TICKET_READ_OWN} - the question is which rows that covers.
 *
 * <p>These tests also pin down the <em>status code</em> choice. An inaccessible ticket answers
 * <strong>404, not 403</strong>: a 403 would confirm the id exists, letting an attacker enumerate valid
 * ids by probing. From the caller's side, a ticket they may not see is indistinguishable from one that
 * does not exist.
 */
@DatabaseIntegrationTest
class ResourceAccessControlTest extends AbstractIntegrationTest {

    private long userA;
    private long userB;
    private long supportId;
    private long applicationId;

    @BeforeEach
    void seed() {
        userA = insertUser("User A", "a.owner@example.test", 8301L, "EMPLOYEE");
        userB = insertUser("User B", "b.other@example.test", 8302L, "EMPLOYEE");
        supportId = insertUser("Support", "support.acl@example.test", 8303L, "IT_SUPPORT");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> ticketBody(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", "d");
        body.put("issueType", "BUG");
        body.put("priority", "LOW");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    private long ticketFor(long raiser, String title) {
        return ((Number) postObjectAs(raiser, "/api/tickets", ticketBody(title)).getBody().get("id"))
                .longValue();
    }

    // ------------------------------------------------ single-resource IDOR

    @Test
    @DisplayName("IDOR: User A cannot read User B's ticket by changing the id")
    void cannotReadAnotherUsersTicket() {
        long ticketOfB = ticketFor(userB, "B's private ticket");

        ResponseEntity<Map<String, Object>> response =
                getObjectAs(userA, "/api/tickets/" + ticketOfB);

        assertThat(response.getStatusCode())
                .as("404 rather than 403, so the response does not confirm the id exists")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .as("the body is a problem document, carrying none of the ticket's own fields")
                .doesNotContainKey("ticketNumber")
                .doesNotContainKey("description")
                .doesNotContainKey("raisedBy")
                .containsEntry("title", "Resource not found");
    }

    @Test
    @DisplayName("IDOR: the response for someone else's ticket is identical to a nonexistent one")
    void inaccessibleAndNonexistentAreIndistinguishable() {
        long ticketOfB = ticketFor(userB, "B's ticket");

        ResponseEntity<String> inaccessible = getRawAs(userA, "/api/tickets/" + ticketOfB);
        ResponseEntity<String> nonexistent = getRawAs(userA, "/api/tickets/999999");

        assertThat(inaccessible.getStatusCode()).isEqualTo(nonexistent.getStatusCode());
        assertThat(inaccessible.getBody())
                .as("""
                        Byte-identical apart from the id in the instance field, so probing cannot \
                        distinguish "exists but not yours" from "does not exist".""")
                .isEqualTo(nonexistent.getBody().replace("999999", String.valueOf(ticketOfB)));
    }

    @Test
    @DisplayName("a user can read their own ticket")
    void canReadOwnTicket() {
        long ownTicket = ticketFor(userA, "A's own ticket");

        ResponseEntity<Map<String, Object>> response =
                getObjectAs(userA, "/api/tickets/" + ownTicket);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("title", "A's own ticket");
    }

    @Test
    @DisplayName("an assignee can read a ticket they did not raise")
    void assigneeCanReadAssignedTicket() {
        long ticketOfB = ticketFor(userB, "B's ticket assigned to A");
        putObjectAs(supportId, "/api/tickets/" + ticketOfB + "/assign/" + userA, Map.of());

        assertThat(getObjectAs(userA, "/api/tickets/" + ticketOfB).getStatusCode())
                .as("being the assignee is involvement; they have to be able to see their work")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("support staff can read any ticket")
    void supportCanReadAnyTicket() {
        long ticketOfB = ticketFor(userB, "B's ticket");

        assertThat(getObjectAs(supportId, "/api/tickets/" + ticketOfB).getStatusCode())
                .as("TICKET_READ_ALL exists so triage is possible at all")
                .isEqualTo(HttpStatus.OK);
    }

    // ----------------------------------------------------- collection IDOR

    @Test
    @DisplayName("BOLA: the ticket list shows only tickets the caller is involved in")
    void listIsRestrictedToOwnTickets() {
        ticketFor(userA, "A-1");
        ticketFor(userA, "A-2");
        ticketFor(userB, "B-1");
        ticketFor(userB, "B-2");
        ticketFor(userB, "B-3");

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(userA, "/api/tickets");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).allSatisfy(
                ticket -> assertThat((String) ticket.get("title")).startsWith("A-"));
        assertThat(response.getHeaders().getFirst("X-Total-Count"))
                .as("""
                        The count must also be restricted. Leaking "5 tickets exist" through pagination \
                        metadata while showing 2 would still disclose information.""")
                .isEqualTo("2");
    }

    @Test
    @DisplayName("BOLA: filtering by another user cannot widen what is visible")
    void filteringByAnotherUserRevealsNothing() {
        ticketFor(userB, "B-1");
        ticketFor(userB, "B-2");

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(userA, "/api/tickets?raisedBy=" + userB);

        assertThat(response.getBody())
                .as("""
                        The ownership restriction is ANDed with the caller's filters inside the SQL, so a \
                        filter can only narrow the result set, never widen it.""")
                .isEmpty();
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("0");
    }

    @Test
    @DisplayName("support staff see the whole list")
    void supportSeesWholeList() {
        ticketFor(userA, "A-1");
        ticketFor(userB, "B-1");
        ticketFor(userB, "B-2");

        assertThat(getArrayAs(supportId, "/api/tickets").getBody()).hasSize(3);
        assertThat(getArrayAs(supportId, "/api/tickets").getHeaders().getFirst("X-Total-Count"))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("an assigned ticket appears in the assignee's list as well as the raiser's")
    void assignedTicketAppearsForBothParties() {
        long ticketOfB = ticketFor(userB, "B's ticket assigned to A");
        putObjectAs(supportId, "/api/tickets/" + ticketOfB + "/assign/" + userA, Map.of());

        assertThat(getArrayAs(userA, "/api/tickets").getBody())
                .as("the assignee is involved, so it is theirs to see")
                .hasSize(1);
        assertThat(getArrayAs(userB, "/api/tickets").getBody())
                .as("the raiser keeps visibility after assignment")
                .hasSize(1);
    }

    @Test
    @DisplayName("an unassigned ticket still appears in its raiser's own list")
    void unassignedTicketAppearsForRaiser() {
        ticketFor(userA, "A's unassigned ticket");

        assertThat(getArrayAs(userA, "/api/tickets").getBody())
                .as("""
                        The ownership restriction ORs raiser and assignee. If the assignee side were an \
                        inner join it would silently drop every unassigned ticket - including the user's \
                        own freshly raised ones.""")
                .hasSize(1);
        assertThat(getArrayAs(userA, "/api/tickets").getHeaders().getFirst("X-Total-Count"))
                .as("and the count must agree, which is where an inner join would show up")
                .isEqualTo("1");
    }

    // ------------------------------------------------------- user resources

    @Test
    @DisplayName("IDOR: a requester cannot read another user's record by id")
    void cannotReadAnotherUserRecord() {
        assertThat(getObjectAs(userA, "/api/users/" + userB).getStatusCode())
                .as("""
                        Locking down the collection is not enough on its own - without this, any \
                        authenticated user could walk /api/users/1..n and rebuild the directory.""")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a user may read their own record")
    void canReadOwnUserRecord() {
        ResponseEntity<Map<String, Object>> response = getObjectAs(userA, "/api/users/" + userA);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("fullName", "User A");
    }

    @Test
    @DisplayName("support staff may read any user record")
    void supportMayReadAnyUserRecord() {
        assertThat(getObjectAs(supportId, "/api/users/" + userB).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("no user response ever contains a password hash")
    void userResponsesNeverContainHashes() {
        insertUserWithPassword("Hashed", "hashed@example.test", 8304L, "EMPLOYEE", "some-long-password");

        String list = getRawAs(supportId, "/api/users").getBody();
        String single = getRawAs(supportId, "/api/users/" + userA).getBody();

        for (String body : List.of(list, single)) {
            assertThat(body.toLowerCase())
                    .doesNotContain("password")
                    .doesNotContain("$2a$");
        }
    }
}
