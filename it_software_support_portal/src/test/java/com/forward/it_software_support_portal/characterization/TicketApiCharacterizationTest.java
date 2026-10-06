package com.forward.it_software_support_portal.characterization;

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
 * Characterization tests for {@code /api/tickets}.
 *
 * <p>Only behaviour considered legitimate today is asserted here. Known defects around ticket
 * creation identity, assignment reporting, status integrity and ticket numbering live under
 * {@code ..regression} so they cannot be mistaken for intended behaviour.
 */
@DatabaseIntegrationTest
class TicketApiCharacterizationTest extends AbstractIntegrationTest {

    private long raiserId;
    private long assigneeId;
    private long supportId;
    private long applicationId;

    @BeforeEach
    void seedReferenceData() {
        // TicketServiceImpl.createTicket currently resolves the raiser as findById(1L), so the
        // first user inserted after RESTART IDENTITY must exist for creation to work at all.
        raiserId = insertUser("Raiser One", "raiser@example.test", 9001L, "EMPLOYEE");
        assigneeId = insertUser("Assignee Two", "assignee@example.test", 9002L, "DEVELOPER");
        // Assignment and status changes require TICKET_ASSIGN / TICKET_STATUS_CHANGE, which only
        // support-side roles hold. A requester performing them is a 403 by design.
        supportId = insertUser("Support Agent", "support@example.test", 9003L, "IT_SUPPORT");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> ticketRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Payroll calculation wrong");
        body.put("description", "Net salary does not match the expected amount");
        body.put("issueType", "BUG");
        body.put("priority", "HIGH");
        body.put("businessImpact", "DEPARTMENT");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    private long createTicket() {
        ResponseEntity<Map<String, Object>> response = postObjectAs(raiserId, "/api/tickets", ticketRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) response.getBody().get("id")).longValue();
    }

    @Test
    @DisplayName("creates a ticket in OPEN status with a generated ticket number")
    void createsTicket() {
        ResponseEntity<Map<String, Object>> response = postObjectAs(raiserId, "/api/tickets", ticketRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull()
                .containsEntry("title", "Payroll calculation wrong")
                .containsEntry("issueType", "BUG")
                .containsEntry("priority", "HIGH")
                .containsEntry("status", "OPEN")
                .containsEntry("businessImpact", "DEPARTMENT")
                .containsEntry("applicationName", "Payroll");
        assertThat((String) body.get("ticketNumber")).startsWith("TKT-");
        assertThat(body.get("createdAt")).isNotNull();
        assertThat(body.get("updatedAt")).isNotNull();
        assertThat(body.get("resolvedAt")).isNull();
    }

    @Test
    @DisplayName("a new ticket is unassigned and has no resolution timestamp")
    void newTicketIsUnassigned() {
        long id = createTicket();

        assertThat(jdbc.queryForObject(
                "SELECT assigned_to FROM tickets WHERE id = ?", Long.class, id)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
    }

    @Test
    @DisplayName("returns a ticket by id")
    void getsTicketById() {
        long id = createTicket();

        ResponseEntity<Map<String, Object>> response = getObjectAs(supportId, "/api/tickets/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .containsEntry("id", (int) id)
                .containsEntry("status", "OPEN");
    }

    @Test
    @DisplayName("lists all tickets")
    void listsTickets() {
        createTicket();
        createTicket();

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(supportId, "/api/tickets");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("assignment persists the assignee and moves the ticket to ASSIGNED")
    void assignmentPersists() {
        long id = createTicket();

        ResponseEntity<Map<String, Object>> response =
                putObjectAs(supportId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM tickets WHERE id = ?", Long.class, id))
                .isEqualTo(assigneeId);
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, id))
                .isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("status update succeeds once a ticket is assigned")
    void statusUpdateAfterAssignment() {
        long id = createTicket();
        putObjectAs(supportId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());

        ResponseEntity<Map<String, Object>> response =
                patchObjectAs(supportId, "/api/tickets/" + id + "/status?status=IN_PROGRESS");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    @DisplayName("moving to RESOLVED records a resolution timestamp")
    void resolvingSetsResolvedAt() {
        long id = createTicket();
        putObjectAs(supportId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());

        patchObjectAs(supportId, "/api/tickets/" + id + "/status?status=RESOLVED");

        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, id))
                .as("resolvedAt is set when a ticket reaches RESOLVED")
                .isNotNull();
    }

    @Test
    @DisplayName("ticket creation writes a CREATED audit row")
    void creationWritesAuditRow() {
        long id = createTicket();

        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT action_type, field_name, old_value, new_value, changed_by "
                        + "FROM ticket_history_tracking WHERE ticket_id = ? ORDER BY id", id);

        assertThat(history).hasSize(1);
        assertThat(history.get(0))
                .containsEntry("action_type", "CREATED")
                .containsEntry("field_name", "status")
                .containsEntry("old_value", null)
                .containsEntry("new_value", "OPEN");
    }

    @Test
    @DisplayName("assignment writes both an ASSIGNED and a STATUS_CHANGED audit row")
    void assignmentWritesAuditRows() {
        long id = createTicket();

        putObjectAs(supportId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());

        List<String> actions = jdbc.queryForList(
                "SELECT action_type FROM ticket_history_tracking WHERE ticket_id = ? ORDER BY id",
                String.class, id);

        assertThat(actions).containsExactly("CREATED", "ASSIGNED", "STATUS_CHANGED");
    }

    @Test
    @DisplayName("history is retrievable newest-first by ticket (uses the V8 index)")
    void historyOrderedNewestFirst() {
        long id = createTicket();
        putObjectAs(supportId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());

        List<java.sql.Timestamp> changedAt = jdbc.queryForList("""
                SELECT changed_at FROM ticket_history_tracking
                WHERE ticket_id = ? ORDER BY changed_at DESC
                """, java.sql.Timestamp.class, id);

        assertThat(changedAt).hasSize(3).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }
}
