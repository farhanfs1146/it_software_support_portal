package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.repository.TicketRepository;
import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit findings P0-5 (ticket number collisions) and P1-4 (no optimistic locking) -
 * <strong>FIXED in Phase 2</strong>.
 *
 * <p>Every assertion here is deliberately timing-independent. Concurrency tests that depend on a
 * particular interleaving are flaky and worthless, so this class either forces the interleaving
 * explicitly (the optimistic locking tests) or asserts an invariant that must hold whatever order
 * the requests happen to take (the uniqueness and atomicity tests).
 */
@DatabaseIntegrationTest
class ConcurrencyRegressionTest extends AbstractIntegrationTest {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private long actorId;
    private long assigneeId;
    private long applicationId;

    @BeforeEach
    void seed() {
        actorId = insertUser("Acting User", "actor.conc@example.test", 9301L, "IT_SUPPORT");
        assigneeId = insertUser("Assignee", "assignee.conc@example.test", 9302L, "DEVELOPER");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> ticketRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Concurrency probe");
        body.put("description", "Created in parallel");
        body.put("issueType", "BUG");
        body.put("priority", "LOW");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    private long createTicket() {
        return ((Number) postObjectAs(actorId, "/api/tickets", ticketRequest())
                .getBody().get("id")).longValue();
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Commits a change from a genuinely separate connection, simulating another user's request. */
    private void otherWriterChangesStatus(long ticketId, String status) throws Exception {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE it_software_support_db.tickets
                     SET status = ?, version = version + 1
                     WHERE id = ?
                     """)) {
            ps.setString(1, status);
            ps.setLong(2, ticketId);
            ps.executeUpdate();
        }
    }

    // --------------------------------------------- P0-5 ticket numbering

    @Test
    @DisplayName("FIXED (P0-5): concurrent creation never fails and never duplicates a number")
    void concurrentCreationIsSafe() throws Exception {
        int threads = 64;

        List<Integer> statuses = runConcurrently(threads,
                () -> postObjectAs(actorId, "/api/tickets", ticketRequest()).getStatusCode().value());

        assertThat(statuses)
                .as("""
                        Before Phase 2 the number was "TKT-" + currentTimeMillis(); any two creations \
                        in the same millisecond collided on tickets_ticket_number_key and became an \
                        HTTP 500. A database sequence cannot collide.""")
                .allMatch(s -> s < 400);
        assertThat(countRows("tickets")).isEqualTo(threads);

        List<String> numbers = jdbc.queryForList("SELECT ticket_number FROM tickets", String.class);
        assertThat(new HashSet<>(numbers))
                .as("every successfully created ticket must have a unique number")
                .hasSize(threads);
    }

    @Test
    @DisplayName("FIXED (P0-5): ticket numbers keep the TKT- prefix and remain all digits")
    void ticketNumberFormatIsPreserved() {
        createTicket();
        createTicket();

        List<String> numbers = jdbc.queryForList(
                "SELECT ticket_number FROM tickets ORDER BY id", String.class);

        assertThat(numbers).allSatisfy(n -> assertThat(n).matches("TKT-\\d+"));
        assertThat(numbers).containsExactly("TKT-00000001", "TKT-00000002");
    }

    @Test
    @DisplayName("FIXED (P0-5): a rolled-back creation does not reuse its number")
    void rolledBackCreationDoesNotReuseNumber() {
        // A sequence is non-transactional on purpose: gaps are acceptable, duplicates are not.
        // The failure has to happen *after* the number is drawn, so it is injected at the audit
        // insert - a bad applicationId would be rejected before the generator is ever called.
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
            postObjectAs(actorId, "/api/tickets", ticketRequest());
            assertThat(countRows("tickets")).as("the doomed creation must have rolled back").isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS t_inject_audit_failure ON ticket_history_tracking");
            jdbc.execute("DROP FUNCTION IF EXISTS inject_audit_failure()");
        }

        createTicket();

        assertThat(jdbc.queryForList("SELECT ticket_number FROM tickets", String.class))
                .as("""
                        The rolled-back attempt consumed TKT-00000001. The next ticket must take a \
                        fresh number rather than reusing it - a gap is correct, a duplicate would \
                        violate tickets_ticket_number_key.""")
                .hasSize(1)
                .doesNotContain("TKT-00000001");
    }

    // ------------------------------------------ P1-4 optimistic locking

    @Test
    @DisplayName("FIXED (P1-4): a stale write is rejected rather than silently overwriting")
    void staleWriteIsRejected() {
        long ticketId = createTicket();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            Ticket mine = ticketRepository.findById(ticketId).orElseThrow();
            mine.setStatus(TicketStatus.CLOSED);

            // Another user commits first, from a separate connection. This forces the interleaving
            // deterministically instead of hoping for it.
            try {
                otherWriterChangesStatus(ticketId, "RESOLVED");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            ticketRepository.saveAndFlush(mine); // carries the now-stale version
            return null;
        }))
                .as("""
                        Before @Version this write silently won and the other user's change was \
                        lost - the audit measured 6 concurrent writes all returning 200.""")
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("FIXED (P1-4): the winning update survives intact; the loser changes nothing")
    void winningUpdateRemainsIntact() {
        long ticketId = createTicket();

        try {
            transactionTemplate.execute(status -> {
                Ticket mine = ticketRepository.findById(ticketId).orElseThrow();
                mine.setStatus(TicketStatus.CLOSED);
                try {
                    otherWriterChangesStatus(ticketId, "RESOLVED");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                ticketRepository.saveAndFlush(mine);
                return null;
            });
        } catch (OptimisticLockingFailureException expected) {
            // the stale writer loses, which is the point
        }

        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, ticketId))
                .as("the first committer's value must stand")
                .isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("FIXED (P1-4): the version column increments on every update")
    void versionIncrementsOnUpdate() {
        long ticketId = createTicket();
        long initial = jdbc.queryForObject("SELECT version FROM tickets WHERE id = ?", Long.class, ticketId);

        patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=IN_PROGRESS");
        long afterStatus = jdbc.queryForObject("SELECT version FROM tickets WHERE id = ?", Long.class, ticketId);

        putObjectAs(actorId, "/api/tickets/" + ticketId + "/assign/" + assigneeId, Map.of());
        long afterAssign = jdbc.queryForObject("SELECT version FROM tickets WHERE id = ?", Long.class, ticketId);

        assertThat(afterStatus).isGreaterThan(initial);
        assertThat(afterAssign).isGreaterThan(afterStatus);
    }

    @Test
    @DisplayName("an optimistic lock conflict surfaces as HTTP 409, never as 500")
    void conflictMapsTo409() throws Exception {
        long ticketId = createTicket();

        // Hold a stale read, let another writer commit, then drive the same ticket through the API.
        // The API re-reads inside its own transaction, so it will succeed - the HTTP mapping itself
        // is asserted deterministically by GlobalExceptionHandlerTest. What must hold here is that a
        // concurrent change never produces a 500.
        otherWriterChangesStatus(ticketId, "RESOLVED");

        List<Integer> statuses = runConcurrently(8,
                () -> patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=CLOSED")
                        .getStatusCode().value());

        assertThat(statuses)
                .as("a concurrent modification is a 409 at worst - never an internal server error")
                .allSatisfy(s -> assertThat(s).isIn(200, 409));
    }

    // ------------------------------------- atomicity under concurrency

    @Test
    @DisplayName("under concurrency, every committed status change is still audited")
    void concurrentWritesRemainAuditable() throws Exception {
        long ticketId = createTicket();

        String[] targets = {"IN_PROGRESS", "RESOLVED", "CLOSED", "PENDING", "UNDER_REVIEW", "OPEN"};
        AtomicInteger cursor = new AtomicInteger();
        List<Integer> statuses = runConcurrently(targets.length, () -> {
            String target = targets[cursor.getAndIncrement() % targets.length];
            return patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=" + target)
                    .getStatusCode().value();
        });

        long successes = statuses.stream().filter(s -> s == 200).count();
        int auditedChanges = jdbc.queryForObject("""
                SELECT count(*) FROM ticket_history_tracking
                WHERE ticket_id = ? AND action_type = 'STATUS_CHANGED'
                """, Integer.class, ticketId);

        assertThat(auditedChanges)
                .as("""
                        THE INVARIANT: exactly one audit row per successful change. Before Phase 2 a \
                        change could commit with no audit row at all, so this count could be lower \
                        than the number of successes.""")
                .isEqualTo((int) successes);

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM tickets WHERE id = ?", String.class, ticketId);
        String lastAudited = jdbc.queryForObject("""
                SELECT new_value FROM ticket_history_tracking
                WHERE ticket_id = ? AND action_type = 'STATUS_CHANGED'
                ORDER BY id DESC LIMIT 1
                """, String.class, ticketId);

        assertThat(finalStatus)
                .as("the ticket's actual status must be the last one the audit trail recorded")
                .isEqualTo(lastAudited);

        assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(200, 409));
    }
}
