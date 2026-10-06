package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit findings P1-5 (no status workflow), P1-6 (resolvedAt never cleared) and P2-12 (assignment
 * is coupled to status - intent unknown).
 *
 * <p>These are separated carefully, because they are not all the same kind of problem:
 *
 * <ul>
 *   <li><strong>P1-6 is fixed.</strong> A reopened ticket used to keep the {@code resolved_at} it
 *       earned earlier, silently corrupting every resolution-time metric.
 *       {@code TicketServiceImpl.applyResolutionTimestamp} now clears it whenever the ticket returns
 *       to open work and preserves it on {@code CLOSED}. Both directions are asserted below, because
 *       over-clearing would destroy the same metric that under-clearing corrupted.
 *   <li><strong>P1-5 is still a defect and still open.</strong> A ticket can jump from {@code CLOSED}
 *       straight to {@code OPEN}. Fixing it needs an explicit transition table, and which transitions
 *       are legal is the business decision recorded in docs/TESTING.md - so the target test stays
 *       disabled. Note that P1-6's fix does not depend on it: keeping a derived column honest about
 *       whatever status was reached holds under any workflow.
 *   <li><strong>P2-12 is ambiguous and is NOT judged here.</strong> See
 *       {@link UndecidedBusinessRules}.
 * </ul>
 */
@DatabaseIntegrationTest
class TicketStatusWorkflowRegressionTest extends AbstractIntegrationTest {

    private long actorId;
    private long assigneeId;
    private long applicationId;

    @BeforeEach
    void seed() {
        actorId = insertUser("Acting User", "actor.flow@example.test", 9201L, "IT_SUPPORT");
        assigneeId = insertUser("Assignee", "assignee.flow@example.test", 9202L, "DEVELOPER");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private long assignedTicket() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Workflow probe");
        body.put("description", "Walks the status machine");
        body.put("issueType", "BUG");
        body.put("priority", "MEDIUM");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");

        long id = ((Number) postObjectAs(actorId, "/api/tickets", body).getBody().get("id")).longValue();
        putObjectAs(actorId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());
        return id;
    }

    private String status(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId);
    }

    @Test
    @DisplayName("documents the status values the domain currently defines")
    void documentsAvailableStatuses() {
        assertThat(Arrays.stream(TicketStatus.values()).map(Enum::name))
                .as("""
                        The enum defines the vocabulary but no transition rules. The code comment in \
                        TicketServiceImpl describes OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> \
                        CLOSED, which the implementation does not enforce.""")
                .containsExactly("OPEN", "UNDER_REVIEW", "ASSIGNED", "IN_PROGRESS",
                        "RESOLVED", "CLOSED", "REOPENED", "PENDING");
    }

    @Test
    @DisplayName("DEFECT: any status transition is accepted, including CLOSED back to OPEN")
    void anyTransitionIsAccepted() {
        long ticketId = assignedTicket();

        for (String next : new String[]{"RESOLVED", "CLOSED", "OPEN", "REOPENED", "PENDING"}) {
            assertThat(patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=" + next)
                    .getStatusCode().is2xxSuccessful())
                    .as("Documents audit finding P1-5: transition to %s was accepted unconditionally.", next)
                    .isTrue();
        }

        assertThat(status(ticketId)).isEqualTo("PENDING");
    }

    /**
     * Replaces the former {@code resolvedAtIsNeverCleared}, which asserted the defect was still
     * present ("the ticket is REOPENED yet still carries a resolution timestamp"). That
     * characterization was correct until P1-6 was fixed and is now the opposite of the required
     * behaviour, so it is gone rather than left to contradict {@link #reopeningClearsResolvedAt}.
     *
     * <p>What remains to pin down is the part that is easy to get wrong in the other direction:
     * {@code CLOSED} must <em>keep</em> the timestamp. A fix that cleared it on every status other
     * than {@code RESOLVED} would pass the reopen test and still destroy the resolution time of every
     * completed ticket - the exact metric P1-6 exists to protect.
     */
    @Test
    @DisplayName("closing a resolved ticket keeps its resolution timestamp")
    void closingKeepsResolvedAt() {
        long ticketId = assignedTicket();

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=RESOLVED");
        java.sql.Timestamp resolvedAt = jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId);
        assertThat(resolvedAt).isNotNull();

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=CLOSED");

        assertThat(status(ticketId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId))
                .as("""
                        CLOSED is the final step of the documented happy path \
                        OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED. A closed ticket was \
                        resolved, so clearing the timestamp here would make every completed ticket \
                        report no resolution time at all.""")
                .isEqualTo(resolvedAt);
    }

    @Test
    @DisplayName("a ticket closed without ever being resolved keeps a null resolution timestamp")
    void closingWithoutResolvingLeavesResolvedAtNull() {
        long ticketId = assignedTicket();

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=CLOSED");

        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId))
                .as("preserving on CLOSED must not mean inventing a resolution that never happened")
                .isNull();
    }

    /**
     * NOT A DEFECT REPORT - an open business question.
     *
     * <p>{@code assignTicket} unconditionally sets the status to {@code ASSIGNED}. Reassigning a
     * ticket that is already {@code IN_PROGRESS} therefore moves it backwards. Whether that is
     * correct cannot be determined from this codebase:
     *
     * <ul>
     *   <li>No requirements document, specification or acceptance criteria exist in the repository.
     *   <li>No test previously established a rule - there were no tests at all.
     *   <li>The only statement of intent is a comment in {@code TicketServiceImpl}
     *       ({@code OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED}), which describes a happy
     *       path and says nothing about reassignment.
     *   <li>Both readings are defensible: resetting to {@code ASSIGNED} signals that the new owner
     *       has not started yet, while preserving {@code IN_PROGRESS} keeps work-in-progress
     *       visible and protects SLA timers.
     * </ul>
     *
     * <p>The tests below therefore <em>record</em> the behaviour without endorsing it. The rule is a
     * business decision that must be confirmed before Phase 4 touches assignment. Nothing in Phase 1
     * changes it.
     */
    @Nested
    @DisplayName("undecided business rules (require a decision before Phase 4)")
    class UndecidedBusinessRules {

        @Test
        @DisplayName("AMBIGUOUS: assignment couples to status and resets IN_PROGRESS to ASSIGNED")
        void assignmentResetsInProgressToAssigned() {
            long ticketId = assignedTicket();
            patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=IN_PROGRESS");
            assertThat(status(ticketId)).isEqualTo("IN_PROGRESS");

            long otherAssignee = insertUser("Other Dev", "other.flow@example.test", 9203L, "DEVELOPER");
            putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + otherAssignee, Map.of());

            assertThat(status(ticketId))
                    .as("""
                            RECORDED, NOT ENDORSED (audit P2-12): reassignment moved the ticket from \
                            IN_PROGRESS back to ASSIGNED. Confirm the intended rule before changing \
                            this - do not treat this test as a specification.""")
                    .isEqualTo("ASSIGNED");
        }

        @Test
        @DisplayName("AMBIGUOUS: reassignment is permitted and audited")
        void reassignmentIsPermitted() {
            long ticketId = assignedTicket();
            long otherAssignee = insertUser("Second Dev", "second.flow@example.test", 9204L, "DEVELOPER");

            assertThat(putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + otherAssignee, Map.of())
                    .getStatusCode().is2xxSuccessful()).isTrue();

            assertThat(jdbc.queryForObject(
                    "SELECT assigned_to FROM tickets WHERE id = ?", Long.class, ticketId))
                    .isEqualTo(otherAssignee);

            assertThat(jdbc.queryForList("""
                    SELECT old_value FROM ticket_history_tracking
                    WHERE ticket_id = ? AND field_name = 'assigned_to' ORDER BY id
                    """, String.class, ticketId))
                    .as("reassignment records the previous assignee")
                    .containsExactly(null, "Assignee");
        }
    }

    @Test
    @Disabled("""
            PHASE 4 REGRESSION TARGET (audit P1-5). Requires an explicit transition table rejecting \
            illegal moves with 409/422. The exact legal set depends on the workflow decision recorded \
            in docs/TESTING.md. Enable once agreed and implemented.""")
    @DisplayName("an illegal transition is rejected")
    void illegalTransitionIsRejected() {
        long ticketId = assignedTicket();
        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=CLOSED");

        assertThat(patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=OPEN")
                .getStatusCode().is4xxClientError())
                .as("CLOSED -> OPEN must not be a silent success")
                .isTrue();
    }

    /** Enabled: audit P1-6 is implemented - {@code TicketServiceImpl.applyResolutionTimestamp}. */
    @Test
    @DisplayName("reopening clears the resolution timestamp")
    void reopeningClearsResolvedAt() {
        long ticketId = assignedTicket();
        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=RESOLVED");

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=REOPENED");

        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId))
                .isNull();
    }

    @Test
    @DisplayName("a ticket reopened after being closed also loses the timestamp")
    void reopeningAfterCloseClearsResolvedAt() {
        long ticketId = assignedTicket();
        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=RESOLVED");
        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=CLOSED");

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=REOPENED");

        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId))
                .as("CLOSED preserves the timestamp, but leaving CLOSED for open work still clears it")
                .isNull();
    }

    @Test
    @DisplayName("resolving again after a reopen re-stamps the timestamp")
    void resolvingAgainReStampsResolvedAt() throws InterruptedException {
        long ticketId = assignedTicket();
        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=RESOLVED");
        java.sql.Timestamp first = jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId);

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=REOPENED");
        Thread.sleep(10);
        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=RESOLVED");

        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM tickets WHERE id = ?", java.sql.Timestamp.class, ticketId))
                .as("the column answers 'when was this last resolved'; the full trail lives in history")
                .isNotNull()
                .isAfter(first);
    }
}
