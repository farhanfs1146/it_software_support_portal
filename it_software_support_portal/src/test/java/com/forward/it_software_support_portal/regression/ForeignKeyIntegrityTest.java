package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit finding P0-8: the schema had zero foreign keys - <strong>FIXED in Phase 2 by V11</strong>.
 *
 * <p><strong>What was broken.</strong> {@code raised_by}, {@code assigned_to},
 * {@code application_id}, {@code ticket_id}, {@code commented_by} and {@code changed_by} were bare
 * {@code BIGINT}s. Nothing stopped a ticket pointing at a deleted user or a nonexistent application,
 * so orphans were inevitable and audit rows could reference users that never existed.
 *
 * <p>All constraints are {@code ON DELETE RESTRICT}. No cascade is used anywhere: deleting a user
 * must not silently erase their tickets, and deleting a ticket must not destroy its audit trail -
 * that would defeat the purpose of having one.
 */
@DatabaseIntegrationTest
class ForeignKeyIntegrityTest extends AbstractIntegrationTest {

    private long userId;
    private long adminId;
    private long applicationId;
    private long ticketId;

    @BeforeEach
    void seed() {
        userId = insertUser("FK User", "fk.user@example.test", 9401L, "EMPLOYEE");
        adminId = insertUser("FK Admin", "fk.admin@example.test", 9402L, "ADMIN");
        applicationId = insertApplication("Payroll", "Salary");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "FK probe");
        body.put("description", "Used to exercise referential integrity");
        body.put("issueType", "BUG");
        body.put("priority", "LOW");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        ticketId = ((Number) postObjectAs(userId, "/api/tickets", body).getBody().get("id")).longValue();
    }

    private void insertTicketWith(long raisedBy, Long assignedTo, long appId) {
        jdbc.update("""
                INSERT INTO tickets (ticket_number, title, description, issue_type, priority, status,
                                     created_at, updated_at, raised_by, assigned_to,
                                     application_id, module_name, version)
                VALUES (?, 'x', 'x', 'BUG', 'LOW', 'OPEN', ?, ?, ?, ?, ?, 'M', 0)
                """, "TKT-FK-" + System.nanoTime(), LocalDateTime.now(), LocalDateTime.now(),
                raisedBy, assignedTo, appId);
    }

    // ----------------------------------------------- valid relationships

    @Test
    @DisplayName("a ticket referencing existing rows is accepted")
    void validRelationshipSucceeds() {
        assertThatCode(() -> insertTicketWith(userId, userId, applicationId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a ticket with no assignee is accepted - assigned_to is nullable by design")
    void nullableAssigneeIsAccepted() {
        assertThatCode(() -> insertTicketWith(userId, null, applicationId))
                .doesNotThrowAnyException();
    }

    // --------------------------------------------- invalid relationships

    @Test
    @DisplayName("a ticket raised by a nonexistent user is rejected")
    void unknownRaiserIsRejected() {
        assertThatThrownBy(() -> insertTicketWith(999_999L, null, applicationId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a ticket assigned to a nonexistent user is rejected")
    void unknownAssigneeIsRejected() {
        assertThatThrownBy(() -> insertTicketWith(userId, 999_999L, applicationId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a ticket for a nonexistent application is rejected")
    void unknownApplicationIsRejected() {
        assertThatThrownBy(() -> insertTicketWith(userId, null, 999_999L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an audit row for a nonexistent ticket is rejected")
    void auditRowForUnknownTicketIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_history_tracking
                    (ticket_id, action_type, field_name, new_value, changed_by, changed_at)
                VALUES (999999, 'STATUS_CHANGED', 'status', 'OPEN', ?, ?)
                """, userId, LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an audit row attributed to a nonexistent user is rejected")
    void auditRowForUnknownActorIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_history_tracking
                    (ticket_id, action_type, field_name, new_value, changed_by, changed_at)
                VALUES (?, 'STATUS_CHANGED', 'status', 'OPEN', 999999, ?)
                """, ticketId, LocalDateTime.now()))
                .as("the audit trail must not be able to name a user that never existed")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a comment on a nonexistent ticket is rejected")
    void commentForUnknownTicketIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_comments (comment, created_at, ticket_id, commented_by)
                VALUES ('x', ?, 999999, ?)
                """, LocalDateTime.now(), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an attachment on a nonexistent ticket is rejected")
    void attachmentForUnknownTicketIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ticket_attachments (file_name, file_path, uploaded_at, ticket_id)
                VALUES ('f.txt', '/tmp/f.txt', ?, 999999)
                """, LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------- delete behaviour

    @Test
    @DisplayName("RESTRICT: a user who raised a ticket cannot be silently deleted")
    void deletingReferencedUserIsRejected() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", userId))
                .as("""
                        No CASCADE anywhere: removing a user must not quietly take their tickets or \
                        audit entries with them. Deliberate removal becomes a deliberate operation.""")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countRows("tickets")).isEqualTo(1);
    }

    @Test
    @DisplayName("RESTRICT: a ticket with audit history cannot be silently deleted")
    void deletingTicketWithHistoryIsRejected() {
        assertThat(countRows("ticket_history_tracking")).isPositive();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM tickets WHERE id = ?", ticketId))
                .as("deleting a ticket must not destroy its audit trail")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countRows("ticket_history_tracking")).isPositive();
    }

    @Test
    @DisplayName("RESTRICT: a referenced application cannot be silently deleted")
    void deletingReferencedApplicationIsRejected() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM applications WHERE id = ?", applicationId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the existing soft-delete path for applications is unaffected")
    void applicationSoftDeleteStillWorks() {
        deleteAs(adminId, "/api/applications/" + applicationId);

        assertThat(jdbc.queryForObject(
                "SELECT active FROM applications WHERE id = ?", Boolean.class, applicationId))
                .as("DELETE /api/applications/{id} deactivates rather than removing, so RESTRICT "
                        + "does not interfere with it")
                .isFalse();
    }

    @Test
    @DisplayName("no orphan rows exist after the migration")
    void noOrphansExist() {
        Integer orphans = jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM tickets t LEFT JOIN users u ON u.id = t.raised_by WHERE u.id IS NULL)
                + (SELECT count(*) FROM tickets t LEFT JOIN users u ON u.id = t.assigned_to
                     WHERE t.assigned_to IS NOT NULL AND u.id IS NULL)
                + (SELECT count(*) FROM tickets t LEFT JOIN applications a ON a.id = t.application_id WHERE a.id IS NULL)
                + (SELECT count(*) FROM ticket_history_tracking h LEFT JOIN tickets t ON t.id = h.ticket_id WHERE t.id IS NULL)
                + (SELECT count(*) FROM ticket_history_tracking h LEFT JOIN users u ON u.id = h.changed_by WHERE u.id IS NULL)
                + (SELECT count(*) FROM ticket_comments c LEFT JOIN tickets t ON t.id = c.ticket_id WHERE t.id IS NULL)
                + (SELECT count(*) FROM ticket_comments c LEFT JOIN users u ON u.id = c.commented_by WHERE u.id IS NULL)
                + (SELECT count(*) FROM ticket_attachments a LEFT JOIN tickets t ON t.id = a.ticket_id WHERE t.id IS NULL)
                """, Integer.class);

        assertThat(orphans).isZero();
    }
}
