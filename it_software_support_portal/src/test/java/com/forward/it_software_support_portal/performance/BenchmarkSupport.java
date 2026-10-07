package com.forward.it_software_support_portal.performance;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Shared measurement and data-seeding machinery for the performance benchmarks.
 *
 * <p>Two deliberate choices make these benchmarks trustworthy:
 *
 * <ul>
 *   <li><strong>SQL statement counts come from Hibernate's own {@code Statistics}</strong>, not from
 *       parsing logs. The count is therefore unaffected by logging configuration - it cannot be
 *       improved by turning logging off.
 *   <li><strong>No timing thresholds are asserted.</strong> Wall-clock numbers vary by machine, so
 *       asserting them produces a flaky suite. Timings are reported for before/after comparison;
 *       what is asserted is the statement-count scaling, which is deterministic.
 * </ul>
 */
abstract class BenchmarkSupport extends AbstractIntegrationTest {

    /** One measured request. */
    record Measurement(int itemsReturned, long sqlStatements, long millis, int payloadBytes) {
    }

    /** Aggregated latencies for a concurrency level. */
    record LatencyProfile(int concurrency, int requests, int failures,
                          long totalMillis, long avgMillis, long p50Millis,
                          long p95Millis, long p99Millis, long maxMillis,
                          long sqlStatements) {

        String row() {
            return String.format(
                    "  %-4d | %7d | %7d | %7d | %7d | %7d | %8d | %8d | %6d",
                    concurrency, requests, avgMillis, p50Millis, p95Millis, p99Millis,
                    maxMillis, totalMillis, failures);
        }

        static String header() {
            return """
                      conc | requests |     avg |     p50 |     p95 |     p99 |      max |    total | fails
                    -------+----------+---------+---------+---------+---------+----------+----------+------""";
        }
    }

    /**
     * Seeds a realistic ticket population.
     *
     * <p>Realistic matters here: the N+1 pattern hides behind low cardinality. If every ticket shares
     * one raiser and one application, Hibernate's persistence context dedupes the lookups and the
     * endpoint looks fast. So tickets are spread across many distinct users and applications, with a
     * mix of statuses, priorities, assigned and unassigned rows, and spread-out timestamps.
     */
    /** Distinguishes repeated seed calls so unique columns never collide. */
    private int seedBatch = 0;

    /** Clears all ticket data, then seeds a fresh realistic population of the requested size. */
    protected void resetAndSeed(int ticketCount) {
        jdbc.execute("""
                TRUNCATE TABLE ticket_history_tracking, ticket_history, ticket_attachments,
                               ticket_comments, tickets, applications, users
                RESTART IDENTITY CASCADE
                """);
        seedRealisticTickets(ticketCount);
    }

    protected void seedRealisticTickets(int ticketCount) {
        int batch = ++seedBatch;
        long codeBase = 10_000_000L * batch;
        String appPrefix = "Bench App b" + batch + "-";
        String ticketPrefix = "TKT-BENCH-b" + batch + "-";

        int users = Math.max(10, Math.min(ticketCount / 2, 500));
        int applications = Math.max(5, Math.min(ticketCount / 10, 200));

        jdbc.update("""
                INSERT INTO users (employee_code, full_name, email, role, active)
                SELECT ? + g, 'Bench User ' || ? || '-' || g,
                       'bench' || ? || '-' || g || '@example.test',
                       (ARRAY['EMPLOYEE','DEVELOPER','IT_SUPPORT','MANAGER'])[1 + (g % 4)], true
                FROM generate_series(1, ?) g
                """, codeBase, batch, batch, users);

        jdbc.update("""
                INSERT INTO applications (app_name, module_name, active)
                SELECT ? || g, 'Module ' || g, true
                FROM generate_series(1, ?) g
                """, appPrefix, applications);

        // ~70% of tickets are assigned; the rest are legitimately unassigned.
        jdbc.update("""
                INSERT INTO tickets (ticket_number, title, description, issue_type, priority, status,
                                     created_at, updated_at, resolved_at, business_impact,
                                     raised_by, assigned_to, application_id, module_name, version)
                SELECT
                    ? || lpad(g::text, 8, '0'),
                    'Benchmark ticket ' || g || ' - payroll discrepancy in module ' || (g % 20),
                    'Detailed description for benchmark ticket ' || g
                        || '. Steps to reproduce, expected and actual behaviour.',
                    (ARRAY['BUG','FEATURE_REQUEST','CHANGE_REQUEST','ACCESS_ISSUE','DATA_CORRECTION',
                           'PERFORMANCE_ISSUE','REPORT_ISSUE','HOTFIX'])[1 + (g % 8)],
                    (ARRAY['LOW','MEDIUM','HIGH','CRITICAL'])[1 + (g % 4)],
                    (ARRAY['OPEN','UNDER_REVIEW','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED',
                           'REOPENED','PENDING'])[1 + (g % 8)],
                    now() - ((g % 365) || ' days')::interval - ((g % 1440) || ' minutes')::interval,
                    now() - ((g % 180) || ' days')::interval,
                    CASE WHEN g % 8 IN (4, 5) THEN now() - ((g % 90) || ' days')::interval END,
                    (ARRAY['USER','DEPARTMENT','ORGANISATION'])[1 + (g % 3)],
                    u_raiser.id,
                    CASE WHEN g % 10 < 7 THEN u_assignee.id END,
                    app.id,
                    'Module ' || (g % 20),
                    0
                FROM generate_series(1, ?) g
                JOIN (SELECT id, row_number() OVER (ORDER BY id) rn FROM users
                      WHERE employee_code >= ? AND employee_code < ?) u_raiser
                     ON u_raiser.rn = 1 + (g % ?)
                JOIN (SELECT id, row_number() OVER (ORDER BY id DESC) rn FROM users
                      WHERE employee_code >= ? AND employee_code < ?) u_assignee
                     ON u_assignee.rn = 1 + (g % ?)
                JOIN (SELECT id, row_number() OVER (ORDER BY id) rn FROM applications
                      WHERE app_name LIKE ?) app
                     ON app.rn = 1 + (g % ?)
                """,
                ticketPrefix, ticketCount,
                codeBase, codeBase + users + 1, users,
                codeBase, codeBase + users + 1, users,
                appPrefix + "%", applications);
    }

    /**
     * Worst-case seeding: every ticket references a <em>distinct</em> raiser, assignee and
     * application.
     *
     * <p>This matters because the N+1 cost is driven by the number of distinct associated entities in
     * the result set, not by the row count: Hibernate's persistence context dedupes repeated
     * references, so a population that reuses users looks far better than one that does not. The
     * original audit measured this distribution (200 tickets produced 401 statements); this method
     * reproduces it so the before/after comparison covers the worst case, not just a flattering one.
     */
    protected void resetAndSeedHighCardinality(int ticketCount) {
        jdbc.execute("""
                TRUNCATE TABLE ticket_history_tracking, ticket_history, ticket_attachments,
                               ticket_comments, tickets, applications, users
                RESTART IDENTITY CASCADE
                """);
        int batch = ++seedBatch;
        long codeBase = 10_000_000L * batch;
        String appPrefix = "HC App b" + batch + "-";
        String ticketPrefix = "TKT-HC-b" + batch + "-";

        jdbc.update("""
                INSERT INTO users (employee_code, full_name, email, role, active)
                SELECT ? + g, 'HC User ' || ? || '-' || g,
                       'hc' || ? || '-' || g || '@example.test', 'EMPLOYEE', true
                FROM generate_series(1, ?) g
                """, codeBase, batch, batch, ticketCount);
        jdbc.update("""
                INSERT INTO applications (app_name, module_name, active)
                SELECT ? || g, 'Module ' || g, true
                FROM generate_series(1, ?) g
                """, appPrefix, ticketCount);
        jdbc.update("""
                INSERT INTO tickets (ticket_number, title, description, issue_type, priority, status,
                                     created_at, updated_at, raised_by, assigned_to,
                                     application_id, module_name, version)
                SELECT ? || lpad(g::text, 8, '0'),
                       'High cardinality ticket ' || g, 'description for ticket ' || g,
                       (ARRAY['BUG','FEATURE_REQUEST','CHANGE_REQUEST','ACCESS_ISSUE'])[1 + (g % 4)],
                       (ARRAY['LOW','MEDIUM','HIGH','CRITICAL'])[1 + (g % 4)],
                       (ARRAY['OPEN','ASSIGNED','IN_PROGRESS','RESOLVED','CLOSED'])[1 + (g % 5)],
                       now() - ((g % 365) || ' days')::interval, now(),
                       raiser.id, assignee.id, app.id, 'Module', 0
                FROM generate_series(1, ?) g
                JOIN (SELECT id, row_number() OVER (ORDER BY id) rn FROM users
                      WHERE employee_code >= ?) raiser ON raiser.rn = g
                JOIN (SELECT id, row_number() OVER (ORDER BY id DESC) rn FROM users
                      WHERE employee_code >= ?) assignee ON assignee.rn = g
                JOIN (SELECT id, row_number() OVER (ORDER BY id) rn FROM applications
                      WHERE app_name LIKE ?) app ON app.rn = g
                """, ticketPrefix, ticketCount, codeBase, codeBase, appPrefix + "%");
    }

    /** Statistics on a real PostgreSQL table are what the planner uses; stale stats skew EXPLAIN. */
    protected void analyzeTables() {
        jdbc.execute("ANALYZE tickets");
        jdbc.execute("ANALYZE users");
        jdbc.execute("ANALYZE applications");
    }

    /**
     * The actor the benchmarks authenticate as.
     *
     * <p>Deliberately IT_SUPPORT, which holds TICKET_READ_ALL. Benchmarking as a requester would
     * measure the ownership-restricted query instead of the unrestricted one, and the point of these
     * numbers is to compare like with like against the Phase 3 baseline. Created on demand because the
     * seeding methods truncate the users table.
     */
    protected Long benchmarkActorId() {
        java.util.List<Long> existing = jdbc.queryForList(
                "SELECT id FROM users WHERE email = ?", Long.class, "benchmark.actor@example.test");
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return insertUser("Benchmark Actor", "benchmark.actor@example.test", 777_000_001L, "IT_SUPPORT");
    }

    private org.springframework.http.HttpEntity<Void> authEntity() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(tokenFor(benchmarkActorId()));
        return new org.springframework.http.HttpEntity<>(headers);
    }

    /** Issues one authenticated GET and measures statements, latency and payload size. */
    protected Measurement measureGet(String path) {
        org.springframework.http.HttpEntity<Void> auth = authEntity();
        long sqlBefore = preparedStatementCount();
        long start = System.nanoTime();
        ResponseEntity<String> response = rest.exchange(
                path, org.springframework.http.HttpMethod.GET, auth, String.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long statements = preparedStatementCount() - sqlBefore;

        String body = response.getBody() == null ? "" : response.getBody();
        int items = countOccurrences(body, "\"ticketNumber\"");
        return new Measurement(items, statements, elapsedMs, body.getBytes().length);
    }

    protected static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    /** Runs a warmup round so query-plan compilation does not land in the measured numbers. */
    protected void warmUp(String path) {
        org.springframework.http.HttpEntity<Void> auth = authEntity();
        for (int i = 0; i < 3; i++) {
            rest.exchange(path, org.springframework.http.HttpMethod.GET, auth, String.class);
        }
    }

    /**
     * Drives {@code concurrency} simultaneous requests and reports the latency distribution.
     *
     * <p>All requests are released from a single latch so they genuinely overlap, which is what makes
     * connection-pool contention visible.
     */
    protected LatencyProfile measureConcurrent(int concurrency, String path) throws Exception {
        // Enough total requests that p95/p99 mean something. One request per thread would make a
        // "p95" identical to the maximum, which is not a percentile at all.
        int requestsPerThread = Math.max(1, (int) Math.ceil(40.0 / concurrency));

        // One token, reused across threads: minting per request would measure BCrypt-free JWT signing
        // rather than the endpoint.
        org.springframework.http.HttpEntity<Void> auth = authEntity();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Callable<List<long[]>>> tasks = new ArrayList<>();
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> {
                    startGate.await();
                    List<long[]> mine = new ArrayList<>();
                    for (int r = 0; r < requestsPerThread; r++) {
                        long begin = System.nanoTime();
                        int status;
                        try {
                            status = rest.exchange(path, org.springframework.http.HttpMethod.GET,
                                auth, String.class).getStatusCode().value();
                        } catch (RuntimeException e) {
                            status = 599;
                        }
                        mine.add(new long[]{(System.nanoTime() - begin) / 1_000_000, status});
                    }
                    return mine;
                });
            }

            long sqlBefore = preparedStatementCount();
            List<Future<List<long[]>>> futures = new ArrayList<>();
            for (Callable<List<long[]>> task : tasks) {
                futures.add(pool.submit(task));
            }
            long wallStart = System.nanoTime();
            startGate.countDown();

            List<Long> latencies = new ArrayList<>();
            int failures = 0;
            for (Future<List<long[]>> f : futures) {
                for (long[] result : f.get(15, TimeUnit.MINUTES)) {
                    latencies.add(result[0]);
                    if (result[1] >= 400) {
                        failures++;
                    }
                }
            }
            long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
            long statements = preparedStatementCount() - sqlBefore;

            Collections.sort(latencies);
            return new LatencyProfile(
                    concurrency, latencies.size(), failures, wallMs,
                    latencies.stream().mapToLong(Long::longValue).sum() / latencies.size(),
                    percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99),
                    latencies.get(latencies.size() - 1), statements);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Nearest-rank percentile on an already-sorted list. */
    private static long percentile(List<Long> sorted, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    /**
     * Hikari's own pool metrics, which is where connection starvation actually shows up.
     * {@code threadsAwaitingConnection} above zero means requests are queueing for a connection.
     */
    protected String poolSnapshot() {
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            var mx = hikari.getHikariPoolMXBean();
            return String.format(
                    "hikari: max=%d active=%d idle=%d total=%d awaitingConnection=%d",
                    hikari.getMaximumPoolSize(), mx.getActiveConnections(), mx.getIdleConnections(),
                    mx.getTotalConnections(), mx.getThreadsAwaitingConnection());
        }
        return "pool metrics unavailable (datasource is " + dataSource.getClass().getSimpleName() + ")";
    }

    protected int maxPoolSize() {
        return dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari
                ? hikari.getMaximumPoolSize() : -1;
    }
}
