package com.forward.it_software_support_portal.performance;

import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The always-on guard that the ticket list stays cheap.
 *
 * <p>This is the inverse of what it asserted before Phase 3. It used to pin down the N+1 defect -
 * that statement count grew with row count. It now asserts the property that replaced it: the number
 * of SQL statements for a page is a small constant, independent of how many tickets exist.
 *
 * <p>Deliberately fast and deterministic. No timing is asserted, because wall-clock figures are
 * machine-specific; what is asserted is the statement count, read from Hibernate's own
 * {@code Statistics} rather than parsed from logs - so it cannot be improved by changing logging.
 *
 * <p>The reportable before/after numbers live in {@code TicketListBenchmark}.
 */
@DatabaseIntegrationTest
class TicketListBaselineTest extends BenchmarkSupport {

    /**
     * A page costs: the page query, plus the count query behind the pagination metadata.
     * The allowance leaves room for the occasional extra statement without letting N-dependence
     * through - at 500 rows, the old implementation would have needed over a thousand.
     */
    private static final int MAX_STATEMENTS_PER_PAGE = 6;

    @Test
    @DisplayName("a page of tickets costs a small constant number of SQL statements")
    void pageCostsConstantStatements() {
        resetAndSeed(50);
        warmUp("/api/tickets");
        Measurement atFifty = measureGet("/api/tickets");

        resetAndSeed(500);
        warmUp("/api/tickets");
        Measurement atFiveHundred = measureGet("/api/tickets");

        System.out.printf("""
                [P1-1 AFTER] statements per page request
                   50 tickets -> %d statements (%d items, %,d B)
                  500 tickets -> %d statements (%d items, %,d B)
                %n""",
                atFifty.sqlStatements(), atFifty.itemsReturned(), atFifty.payloadBytes(),
                atFiveHundred.sqlStatements(), atFiveHundred.itemsReturned(), atFiveHundred.payloadBytes());

        assertThat(atFiveHundred.sqlStatements())
                .as("""
                        THE PHASE 3 INVARIANT: a page must cost the same whether the table holds 50 \
                        rows or 500. Before Phase 3 this scaled as 2N+1 in the worst case - 500 \
                        tickets cost 1,001 statements.""")
                .isEqualTo(atFifty.sqlStatements())
                .isLessThanOrEqualTo(MAX_STATEMENTS_PER_PAGE);
    }

    @Test
    @DisplayName("statement count is unaffected by how many distinct users and applications appear")
    void statementCountIsUnaffectedByCardinality() {
        // High cardinality is what made the old implementation worst-case: every distinct referenced
        // entity cost its own select, because the persistence context had nothing to dedupe.
        resetAndSeedHighCardinality(200);
        warmUp("/api/tickets");
        Measurement highCardinality = measureGet("/api/tickets");

        System.out.printf(
                "[P1-1 AFTER] 200 tickets, all references distinct -> %d statements (was 401)%n%n",
                highCardinality.sqlStatements());

        assertThat(highCardinality.sqlStatements())
                .as("joins resolve the names in the page query, so cardinality is irrelevant now")
                .isLessThanOrEqualTo(MAX_STATEMENTS_PER_PAGE);
    }

    @Test
    @DisplayName("the response is bounded: a page never returns the whole table")
    void responseIsBounded() {
        resetAndSeed(500);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(benchmarkActorId(), "/api/tickets");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("the default page size caps the response regardless of table size")
                .hasSize(20);
        assertThat(response.getHeaders().getFirst("X-Total-Count"))
                .as("clients still learn the true total, from a header")
                .isEqualTo("500");
    }

    @Test
    @DisplayName("payload size stays flat as the table grows")
    void payloadSizeStaysFlat() {
        resetAndSeed(100);
        int smallPayload = measureGet("/api/tickets").payloadBytes();

        resetAndSeed(2_000);
        int largePayload = measureGet("/api/tickets").payloadBytes();

        System.out.printf("[P1-1 AFTER] payload: 100 tickets -> %,d B | 2,000 tickets -> %,d B%n%n",
                smallPayload, largePayload);

        assertThat(largePayload)
                .as("""
                        Before Phase 3, 5,100 tickets produced a 1.75 MB response. A page is bounded, \
                        so growth in the table must not grow the response. Allowing 25%% slack for \
                        per-row text length varying between seeds.""")
                .isLessThan((int) (smallPayload * 1.25));
    }
}
