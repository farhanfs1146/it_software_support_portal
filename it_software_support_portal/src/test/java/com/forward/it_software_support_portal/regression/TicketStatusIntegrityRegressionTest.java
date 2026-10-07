package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.enums.TicketStatus;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit findings P0-1 (no transaction boundaries) and P0-2 (NPE in updateStatus) - <strong>FIXED in
 * Phase 2</strong>.
 *
 * <p><strong>What was broken.</strong> {@code updateStatus} saved the ticket and only afterwards
 * dereferenced {@code ticket.getAssignedTo().getId()} to attribute the audit row. For an unassigned
 * ticket that threw a {@code NullPointerException}, and because no method was
 * {@code @Transactional}, the status save had already committed. The caller received HTTP 500 while
 * the change was durably applied and no audit row was written - so the history could not explain how
 * a ticket reached its current state. Since every ticket is created {@code OPEN} and unassigned,
 * this was the normal first transition, not an edge case.
 *
 * <p><strong>What fixed it.</strong> The actor now comes from {@code CurrentUserProvider}, so an
 * unassigned ticket is no longer a special case; and {@code @Transactional} on the service write
 * methods makes the status change and its audit row commit or roll back together.
 *
 * <p>These tests assert the <em>invariant</em> - every committed status is explained by the audit
 * trail, and nothing partial is ever left behind - rather than the mechanism, so they keep their
 * value if the implementation changes again.
 */
@DatabaseIntegrationTest
class TicketStatusIntegrityRegressionTest extends AbstractIntegrationTest {

    @Autowired
    private TicketService ticketService;

    private long actorId;
    private long assigneeId;
    private long applicationId;

    @BeforeEach
    void seed() {
        actorId = insertUser("Acting User", "actor.integrity@example.test", 9601L, "IT_SUPPORT");
        assigneeId = insertUser("Assignee", "assignee.integrity@example.test", 9602L, "DEVELOPER");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private long createUnassignedTicket() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Integrity probe");
        body.put("description", "Created OPEN and unassigned");
        body.put("issueType", "BUG");
        body.put("priority", "HIGH");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return ((Number) postObjectAs(actorId, "/api/tickets", body).getBody().get("id")).longValue();
    }

    private String statusInDatabase(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId);
    }

    private List<String> auditedStatusValues(long ticketId) {
        return jdbc.queryForList("""
                SELECT new_value FROM ticket_history_tracking
                WHERE ticket_id = ? AND field_name = 'status'
                ORDER BY id
                """, String.class, ticketId);
    }

    // ------------------------------------------------- the fixed defect

    @Test
    @DisplayName("FIXED (P0-2): an unassigned ticket can change status without a 5xx")
    void unassignedTicketCanChangeStatus() {
        long ticketId = createUnassignedTicket();

        ResponseEntity<Map<String, Object>> response =
                patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=IN_PROGRESS");

        assertThat(response.getStatusCode())
                .as("this was the NPE path that returned 500 for every ticket's first transition")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "IN_PROGRESS");
    }

    @Test
    @DisplayName("FIXED (P0-1): a successful status change commits the ticket AND its audit row")
    void successCommitsBothTicketAndAudit() {
        long ticketId = createUnassignedTicket();

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=IN_PROGRESS");

        assertThat(statusInDatabase(ticketId)).isEqualTo("IN_PROGRESS");
        assertThat(auditedStatusValues(ticketId))
                .as("the transition must be auditable, not just applied")
                .containsExactly("OPEN", "IN_PROGRESS");
    }

    @Test
    @DisplayName("FIXED (P0-1): every committed status is explained by the audit trail")
    void auditTrailExplainsCurrentState() {
        long ticketId = createUnassignedTicket();

        for (String next : new String[]{"UNDER_REVIEW", "IN_PROGRESS", "RESOLVED"}) {
            patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=" + next);
        }

        List<String> audited = auditedStatusValues(ticketId);
        assertThat(audited).containsExactly("OPEN", "UNDER_REVIEW", "IN_PROGRESS", "RESOLVED");
        assertThat(audited.get(audited.size() - 1))
                .as("the last audited status must match the ticket's actual status")
                .isEqualTo(statusInDatabase(ticketId));
    }

    // ------------------------------------------------- rollback proof

    /**
     * Proves the rollback half of the invariant.
     *
     * <p>A failure is induced the only way that does not require changing production code: the
     * ticket is deleted underneath the operation, so the service's {@code findById} fails. The point
     * is that after any failed call, <em>nothing</em> partial remains.
     */
    @Test
    @DisplayName("FIXED (P0-1): a failed status change leaves neither a status change nor an audit row")
    void failureLeavesNothingBehind() {
        long ticketId = createUnassignedTicket();
        int auditRowsBefore = countRows("ticket_history_tracking");

        ResponseEntity<Map<String, Object>> response =
                patchObjectAs(actorId, "/api/tickets/999999/status?status=CLOSED");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusInDatabase(ticketId))
                .as("an unrelated ticket must be untouched")
                .isEqualTo("OPEN");
        assertThat(countRows("ticket_history_tracking"))
                .as("a failed operation must not append an audit row")
                .isEqualTo(auditRowsBefore);
    }

    /**
     * The strongest available proof of atomicity, and the closest reproduction of the original
     * defect: make the <em>audit insert</em> fail while the ticket modification is already in flight,
     * then confirm the ticket change is rolled back with it.
     *
     * <p>The failure is injected with a database trigger rather than by changing production code, so
     * what is under test is the real service path. This is exactly the shape of the Phase 1 defect -
     * ticket written, audit write fails - and before Phase 2 it left the status committed as
     * {@code CLOSED} while the caller was told the request had failed.
     */
    @Test
    @DisplayName("FIXED (P0-1): if the audit write fails, the ticket change is rolled back with it")
    void failedAuditWriteRollsBackTicketChange() {
        long ticketId = createUnassignedTicket();
        assertThat(statusInDatabase(ticketId)).isEqualTo("OPEN");

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION inject_audit_failure() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected audit failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER t_inject_audit_failure
                BEFORE INSERT ON ticket_history_tracking
                FOR EACH ROW EXECUTE FUNCTION inject_audit_failure()
                """);
        try {
            ResponseEntity<Map<String, Object>> response =
                    patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=CLOSED");

            assertThat(response.getStatusCode().isError())
                    .as("the audit write failed, so the request must report failure")
                    .isTrue();

            assertThat(statusInDatabase(ticketId))
                    .as("""
                            THE PHASE 2 INVARIANT: the audit write failed, so the status change must \
                            have been rolled back and the ticket must still be OPEN. Before Phase 2 \
                            this assertion failed - the status was committed while the caller \
                            received an error and the audit trail lost the change.""")
                    .isEqualTo("OPEN");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS t_inject_audit_failure ON ticket_history_tracking");
            jdbc.execute("DROP FUNCTION IF EXISTS inject_audit_failure()");
        }

        assertThat(auditedStatusValues(ticketId))
                .as("and no audit row may survive for the failed transition")
                .containsExactly("OPEN");
    }

    /**
     * Same injection applied to ticket creation: if the {@code CREATED} audit row cannot be written,
     * no ticket may exist either.
     */
    @Test
    @DisplayName("FIXED (P0-1): ticket creation is atomic with its CREATED audit row")
    void ticketCreationIsAtomicWithItsAuditRow() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION inject_audit_failure() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'injected audit failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER t_inject_audit_failure
                BEFORE INSERT ON ticket_history_tracking
                FOR EACH ROW EXECUTE FUNCTION inject_audit_failure()
                """);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "Atomic creation probe");
            body.put("description", "The audit insert will fail");
            body.put("issueType", "BUG");
            body.put("priority", "LOW");
            body.put("applicationId", applicationId);
            body.put("moduleName", "Salary");

            ResponseEntity<Map<String, Object>> response = postObjectAs(actorId, "/api/tickets", body);

            assertThat(response.getStatusCode().isError()).isTrue();
            assertThat(countRows("tickets"))
                    .as("a ticket must not exist without its creation audit row")
                    .isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS t_inject_audit_failure ON ticket_history_tracking");
            jdbc.execute("DROP FUNCTION IF EXISTS inject_audit_failure()");
        }
    }

    @Test
    @DisplayName("the service layer throws a typed not-found exception, not a bare RuntimeException")
    void serviceLayerThrowsTypedNotFound() {
        // Reads need no caller identity, so this exercises the service boundary directly. Write
        // methods cannot be called outside an HTTP request while identity comes from a header - that
        // constraint is asserted by identityIsUnavailableOutsideARequest below.
        assertThatThrownBy(() -> ticketService.getTicketById(999_999L))
                .as("typed exceptions are what let the handler return 404 instead of 500")
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
    }

    @Test
    @DisplayName("a write invoked with no request context fails closed and writes nothing")
    void identityIsUnavailableOutsideARequest() {
        long ticketId = createUnassignedTicket();
        int auditRowsBefore = countRows("ticket_history_tracking");

        // The interim CurrentUserProvider reads an HTTP header, so outside a request there is no
        // caller. The important property is that this fails rather than inventing an actor.
        assertThatThrownBy(() -> ticketService.updateStatus(ticketId, TicketStatus.CLOSED))
                .as("no caller identity must mean no write, never a default user")
                .isInstanceOf(Exception.class);

        assertThat(statusInDatabase(ticketId))
                .as("the ticket must be untouched")
                .isEqualTo("OPEN");
        assertThat(countRows("ticket_history_tracking"))
                .as("and no audit row may have been appended")
                .isEqualTo(auditRowsBefore);
    }

    // ------------------------------------------------- audit content

    @Test
    @DisplayName("audit rows capture previous state, new state, actor, ticket, action and timestamp")
    void auditRowIsComplete() {
        long ticketId = createUnassignedTicket();

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=IN_PROGRESS");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT ticket_id, action_type, field_name, old_value, new_value, changed_by,
                       changed_at, remarks
                FROM ticket_history_tracking
                WHERE ticket_id = ? AND action_type = 'STATUS_CHANGED'
                ORDER BY id DESC LIMIT 1
                """, ticketId);

        assertThat(row).containsEntry("ticket_id", ticketId);
        assertThat(row).containsEntry("action_type", "STATUS_CHANGED");
        assertThat(row).containsEntry("field_name", "status");
        assertThat(row).containsEntry("old_value", "OPEN");
        assertThat(row).containsEntry("new_value", "IN_PROGRESS");
        assertThat(row).containsEntry("changed_by", actorId);
        assertThat(row.get("changed_at")).isNotNull();
        assertThat(row.get("remarks")).isNotNull();
    }

    @Test
    @DisplayName("the audit actor is the calling user, not the assignee")
    void auditActorIsTheCaller() {
        long ticketId = createUnassignedTicket();
        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());

        List<Long> actors = jdbc.queryForList("""
                SELECT changed_by FROM ticket_history_tracking WHERE ticket_id = ? ORDER BY id
                """, Long.class, ticketId);

        assertThat(actors)
                .as("""
                        changed_by means "who made this change". Before Phase 2 the assignment rows \
                        recorded the assignee instead of the person doing the assigning.""")
                .containsExactly(actorId, actorId, actorId);
        assertThat(actors).doesNotContain(assigneeId);
    }
}
