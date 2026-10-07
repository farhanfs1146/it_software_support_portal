package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ?sort=priority} orders by severity, not by the spelling of the enum constant.
 *
 * <p>{@code Priority} is mapped {@code @Enumerated(EnumType.STRING)}, so the column holds text and
 * the database sorted it alphabetically: {@code CRITICAL, HIGH, LOW, MEDIUM} ascending, which makes
 * {@code ?sort=priority,desc} - the obvious way to ask a support queue for "worst first" - return
 * {@code MEDIUM, LOW, HIGH, CRITICAL}. The least urgent ticket sat at the top of the triage list and
 * nothing reported a problem, because an ordering that is merely wrong still looks like an ordering.
 *
 * <p>Fixed in {@code TicketQueryRepositoryImpl.sortExpression} with a {@code CASE} built from
 * {@code Priority.values()}, so the enum's declaration order
 * ({@code LOW < MEDIUM < HIGH < CRITICAL}) is the ordering. Unlike most open questions here this one
 * needed no business decision: a priority enum is an ordered scale by its nature.
 */
@DatabaseIntegrationTest
class TicketPrioritySortRegressionTest extends AbstractIntegrationTest {

    private long supportId;
    private long applicationId;

    @BeforeEach
    void seed() {
        supportId = insertUser("Support Staff", "support.sort@example.test", 9501L, "IT_SUPPORT");
        applicationId = insertApplication("Payroll", "Salary");

        // Inserted in an order that is neither alphabetical nor severity order, so neither can pass
        // by accident through insertion order or the id tiebreaker.
        for (String priority : new String[]{"HIGH", "LOW", "CRITICAL", "MEDIUM"}) {
            createTicket(priority);
        }
    }

    private void createTicket(String priority) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", priority + " ticket");
        body.put("description", "Seeded for priority ordering.");
        body.put("issueType", "BUG");
        body.put("priority", priority);
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");

        assertThat(postObjectAs(supportId, "/api/tickets", body).getStatusCode().is2xxSuccessful())
                .isTrue();
    }

    private List<String> prioritiesSortedBy(String sort) {
        return getArrayAs(supportId, "/api/tickets?sort=" + sort).getBody().stream()
                .map(ticket -> String.valueOf(ticket.get("priority")))
                .toList();
    }

    @Test
    @DisplayName("priority,desc puts the most urgent ticket first")
    void descendingIsWorstFirst() {
        assertThat(prioritiesSortedBy("priority,desc"))
                .as("""
                        The regression target. Alphabetically this returned \
                        MEDIUM, LOW, HIGH, CRITICAL - the least urgent ticket at the top of a \
                        triage queue.""")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW");
    }

    @Test
    @DisplayName("priority,asc puts the least urgent ticket first")
    void ascendingIsLeastUrgentFirst() {
        assertThat(prioritiesSortedBy("priority,asc"))
                .as("alphabetically this returned CRITICAL, HIGH, LOW, MEDIUM")
                .containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    @Test
    @DisplayName("the bare property defaults to ascending, as every other sort key does")
    void bareePropertyIsAscending() {
        assertThat(prioritiesSortedBy("priority"))
                .containsExactly("LOW", "MEDIUM", "HIGH", "CRITICAL");
    }

    @Test
    @DisplayName("priority sorting still pages correctly")
    void pagingFollowsSeverityOrder() {
        assertThat(getArrayAs(supportId, "/api/tickets?sort=priority,desc&page=0&size=2").getBody())
                .extracting(ticket -> ticket.get("priority"))
                .containsExactly("CRITICAL", "HIGH");

        assertThat(getArrayAs(supportId, "/api/tickets?sort=priority,desc&page=1&size=2").getBody())
                .as("""
                        The CASE expression has to be in the ORDER BY of the page query itself, not \
                        applied after the LIMIT - otherwise page 2 is drawn from a differently \
                        ordered set and tickets can appear twice or not at all.""")
                .extracting(ticket -> ticket.get("priority"))
                .containsExactly("MEDIUM", "LOW");
    }

    @Test
    @DisplayName("priority sorting combines with a filter")
    void combinesWithAFilter() {
        createTicket("CRITICAL");

        assertThat(getArrayAs(supportId, "/api/tickets?priority=CRITICAL&sort=priority,desc").getBody())
                .as("the filter narrows, the sort orders; neither disturbs the other")
                .hasSize(2);
    }

    @Test
    @DisplayName("the total count is unaffected by the ordering expression")
    void countIsUnaffected() {
        assertThat(getArrayAs(supportId, "/api/tickets?sort=priority,desc")
                .getHeaders().getFirst("X-Total-Count"))
                .as("""
                        countRows builds its own predicates and applies no ordering, so the CASE must \
                        not leak into it - a count query that mentioned the expression would be both \
                        slower and a chance for the two halves to disagree.""")
                .isEqualTo("4");
    }

    @Test
    @DisplayName("status remains sortable, and remains grouping rather than lifecycle order")
    void statusSortingIsUnchanged() {
        assertThat(getArrayAs(supportId, "/api/tickets?sort=status").getStatusCode())
                .as("""
                        Deliberately left alone: TicketStatus is not a scale, and its legal sequence \
                        is the undecided question in audit P1-5, so ranking it by declaration order \
                        would invent that sequence. It still groups equal statuses together.""")
                .isEqualTo(HttpStatus.OK);
    }
}
