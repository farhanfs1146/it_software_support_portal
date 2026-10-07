package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.service.TicketService;
import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit finding P1-7: the API misreported assignment - <strong>FIXED in Phase 2</strong>.
 *
 * <p><strong>What was broken.</strong> {@code TicketServiceImpl.mapToResponse} had its
 * {@code .assignedTo(...)} line commented out, so {@code TicketResponse.assignedTo} was always
 * {@code null} even when the database held an assignee. A support engineer reading the API saw an
 * unassigned ticket that was in fact assigned.
 *
 * <p>The DTO field name and the API contract are unchanged - only the mapping was restored, with a
 * null-safe accessor because an unassigned ticket is legitimate.
 *
 * <p>Covers the whole path the audit called out: database assignment to service to mapper to API
 * response.
 */
@DatabaseIntegrationTest
class TicketAssignmentResponseRegressionTest extends AbstractIntegrationTest {

    @Autowired
    private TicketService ticketService;

    private long actorId;
    private long assigneeId;
    private long applicationId;

    @BeforeEach
    void seed() {
        actorId = insertUser("Acting User", "actor.assign@example.test", 9800L, "IT_SUPPORT");
        assigneeId = insertUser("Bob Developer", "bob.assign@example.test", 9802L, "DEVELOPER");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private long createTicket() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Assignment probe");
        body.put("description", "Checks the assignedTo field");
        body.put("issueType", "BUG");
        body.put("priority", "MEDIUM");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return ((Number) postObjectAs(actorId, "/api/tickets", body).getBody().get("id")).longValue();
    }

    @Test
    @DisplayName("FIXED (P1-7): the assign response reports the assignee")
    void assignResponseReportsAssignee() {
        long ticketId = createTicket();

        ResponseEntity<Map<String, Object>> response =
                putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("assignedTo", "Bob Developer");
    }

    @Test
    @DisplayName("FIXED (P1-7): GET by id reports the assignee, matching the database")
    void getByIdReportsAssignee() {
        long ticketId = createTicket();
        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());

        // Ground truth first, so a failure distinguishes a mapping bug from a persistence bug.
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM tickets WHERE id = ?", Long.class, ticketId))
                .isEqualTo(assigneeId);

        assertThat(getObjectAs(actorId, "/api/tickets/" + ticketId).getBody())
                .as("the API must not report null while the database says user %d", assigneeId)
                .containsEntry("assignedTo", "Bob Developer");
    }

    @Test
    @DisplayName("FIXED (P1-7): the list endpoint reports the assignee too")
    void listEndpointReportsAssignee() {
        long ticketId = createTicket();
        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());

        assertThat(getArrayAs(actorId, "/api/tickets").getBody())
                .singleElement()
                .satisfies(ticket -> assertThat(ticket).containsEntry("assignedTo", "Bob Developer"));
    }

    @Test
    @DisplayName("FIXED (P1-7): the service layer itself returns the assignee")
    void serviceLayerReportsAssignee() {
        long ticketId = createTicket();
        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());

        // Since Phase 4 the read path performs a resource-level authorization check, so calling the
        // service directly needs a security context. Establishing it from a real token rather than a
        // mock principal keeps this a test of the production path.
        TicketResponse response = asAuthenticated(actorId, () -> ticketService.getTicketById(ticketId));

        assertThat(response.getAssignedTo()).isEqualTo("Bob Developer");
    }

    @Test
    @DisplayName("the service read path refuses to run without an authenticated caller")
    void serviceLayerRequiresAuthentication() {
        long ticketId = createTicket();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ticketService.getTicketById(ticketId))
                .as("""
                        Fails closed rather than defaulting to an unrestricted read. The authorization \
                        check is in the service, so it holds however the service is reached.""")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("an unassigned ticket reports a null assignee without failing")
    void unassignedTicketMapsToNull() {
        ResponseEntity<Map<String, Object>> created = postObjectAs(actorId, "/api/tickets", Map.of(
                "title", "Unassigned",
                "description", "No assignee yet",
                "issueType", "BUG",
                "priority", "LOW",
                "applicationId", applicationId,
                "moduleName", "Salary"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody())
                .as("null-safe mapping: an unassigned ticket is legitimate, not an error")
                .containsEntry("assignedTo", null);
    }

    @Test
    @DisplayName("reassignment updates the reported assignee")
    void reassignmentUpdatesReportedAssignee() {
        long ticketId = createTicket();
        long otherAssignee = insertUser("Carol Developer", "carol.assign@example.test", 9803L, "DEVELOPER");

        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());
        assertThat(getObjectAs(actorId, "/api/tickets/" + ticketId).getBody())
                .containsEntry("assignedTo", "Bob Developer");

        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + otherAssignee, Map.of());
        assertThat(getObjectAs(actorId, "/api/tickets/" + ticketId).getBody())
                .containsEntry("assignedTo", "Carol Developer");
    }

    @Test
    @DisplayName("assigning to a user that does not exist is a client error, and changes nothing")
    void assigningToUnknownUserIsRejected() {
        long ticketId = createTicket();

        ResponseEntity<Map<String, Object>> response =
                putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/999999", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM tickets WHERE id = ?", Long.class, ticketId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId))
                .as("the status must not have been forced to ASSIGNED by a failed assignment")
                .isEqualTo("OPEN");
    }
}
