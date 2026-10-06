package com.forward.it_software_support_portal.performance;

import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeatable before/after benchmark for the ticket-list endpoint.
 *
 * <p>Not an ordinary test: it reports numbers rather than asserting timings, because wall-clock
 * figures are machine-specific and asserting them would be flaky. The one thing it does assert is
 * that every request succeeded, so a "fast" result cannot come from silent failures.
 *
 * <p>Run it deliberately - it seeds up to 5,000 tickets and drives 50 concurrent requests:
 *
 * <pre>
 * mvnw test -Dtest=TicketListBenchmark -Djunit.jupiter.conditions.deactivate=*
 * </pre>
 *
 * <p>The scaling invariant that must hold on every build lives in {@code TicketListBaselineTest}
 * instead, which is fast and always on.
 */
@DatabaseIntegrationTest
class TicketListBenchmark extends BenchmarkSupport {

    /** The path under measurement. Pagination parameters are appended where supported. */
    private static final String LIST_PATH = "/api/tickets";

    @Test
    @org.junit.jupiter.api.Disabled("""
            BENCHMARK - run deliberately: \
            mvnw test -Dtest=TicketListBenchmark#datasetScaling -Djunit.jupiter.conditions.deactivate=*""")
    @DisplayName("BENCHMARK: ticket list across dataset sizes")
    void datasetScaling() {
        int[] sizes = {50, 200, 500, 1_000, 5_000};
        Map<Integer, Measurement> results = new LinkedHashMap<>();

        for (int size : sizes) {
            // Each size is measured on a freshly seeded database so the numbers are independent.
            resetAndSeed(size);
            analyzeTables();

            warmUp(LIST_PATH);
            Measurement m = measureGet(LIST_PATH);
            results.put(size, m);

            assertThat(m.itemsReturned())
                    .as("a measurement with no rows returned would be meaningless")
                    .isPositive();
        }

        System.out.println("""

                ================ TICKET LIST: DATASET SCALING ================
                  tickets | returned |  SQL stmts | stmts/row |  latency |     payload
                ----------+----------+------------+-----------+----------+------------""");
        results.forEach((size, m) -> System.out.printf(
                "  %7d | %8d | %10d | %9.2f | %6d ms | %,10d B%n",
                size, m.itemsReturned(), m.sqlStatements(),
                m.sqlStatements() / (double) size, m.millis(), m.payloadBytes()));
        System.out.println("""
                -------------------------------------------------------------
                  stmts/row trending to 0 means query count is independent of N.
                  A flat ~2.0 is the 2N+1 pattern.
                """);
    }

    @Test
    @org.junit.jupiter.api.Disabled("""
            BENCHMARK - run deliberately: \
            mvnw test -Dtest=TicketListBenchmark#worstCaseScaling -Djunit.jupiter.conditions.deactivate=*""")
    @DisplayName("BENCHMARK: ticket list, worst-case cardinality (every ticket distinct)")
    void worstCaseScaling() {
        int[] sizes = {50, 200, 500, 1_000};
        Map<Integer, Measurement> results = new LinkedHashMap<>();

        for (int size : sizes) {
            resetAndSeedHighCardinality(size);
            analyzeTables();
            warmUp(LIST_PATH);
            results.put(size, measureGet(LIST_PATH));
        }

        System.out.println("""

                ======= TICKET LIST: WORST-CASE CARDINALITY (all references distinct) =======
                  tickets | returned |  SQL stmts | stmts/row |  latency |     payload
                ----------+----------+------------+-----------+----------+------------""");
        results.forEach((size, m) -> System.out.printf(
                "  %7d | %8d | %10d | %9.2f | %6d ms | %,10d B%n",
                size, m.itemsReturned(), m.sqlStatements(),
                m.sqlStatements() / (double) size, m.millis(), m.payloadBytes()));
        System.out.println("""
                ----------------------------------------------------------------------------
                  This is the distribution the original audit measured: ~2.0 stmts/row (2N+1).
                """);
    }

    @Test
    @org.junit.jupiter.api.Disabled("""
            BENCHMARK - run deliberately: \
            mvnw test -Dtest=TicketListBenchmark#concurrencyScaling -Djunit.jupiter.conditions.deactivate=*""")
    @DisplayName("BENCHMARK: ticket list under concurrent load")
    void concurrencyScaling() throws Exception {
        resetAndSeed(1_000);
        analyzeTables();
        warmUp(LIST_PATH);

        System.out.printf("""

                ============ TICKET LIST: CONCURRENT LOAD (1,000 tickets) ============
                  %s
                %n""", poolSnapshot());
        System.out.println(LatencyProfile.header());

        for (int concurrency : new int[]{1, 5, 10, 25, 50}) {
            LatencyProfile profile = measureConcurrent(concurrency, LIST_PATH);
            System.out.println(profile.row());

            assertThat(profile.failures())
                    .as("a fast result produced by failed requests would be meaningless")
                    .isZero();
        }
        System.out.println("  (all times in ms)  " + poolSnapshot());
        System.out.println();
    }

    @Test
    @org.junit.jupiter.api.Disabled("""
            BENCHMARK - run deliberately: \
            mvnw test -Dtest=TicketListBenchmark#singleTicketRetrieval -Djunit.jupiter.conditions.deactivate=*""")
    @DisplayName("BENCHMARK: single ticket retrieval")
    void singleTicketRetrieval() {
        seedRealisticTickets(1_000);
        analyzeTables();
        Long id = jdbc.queryForObject("SELECT min(id) FROM tickets", Long.class);

        warmUp("/api/tickets/" + id);
        Measurement m = measureGet("/api/tickets/" + id);

        System.out.printf("""

                ================ SINGLE TICKET RETRIEVAL ================
                  GET /api/tickets/%d
                    SQL statements : %d
                    latency        : %d ms
                    payload        : %,d B
                ---------------------------------------------------------
                %n""", id, m.sqlStatements(), m.millis(), m.payloadBytes());
    }
}
