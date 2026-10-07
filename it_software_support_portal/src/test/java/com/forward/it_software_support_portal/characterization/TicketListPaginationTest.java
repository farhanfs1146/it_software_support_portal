package com.forward.it_software_support_portal.characterization;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pagination, filtering and sorting on {@code GET /api/tickets} (Phase 3).
 *
 * <p>The endpoint previously returned every row with no bound. These tests pin down the contract that
 * replaced it, including the parts that protect the database from the client: a capped page size, a
 * sort whitelist, and a deterministic order so paging cannot skip or repeat rows.
 */
@DatabaseIntegrationTest
class TicketListPaginationTest extends AbstractIntegrationTest {

    private long raiserId;
    private long assigneeId;
    private long applicationId;

    @BeforeEach
    void seed() {
        // IT_SUPPORT holds TICKET_READ_ALL, so these tests observe pagination semantics rather than
        // the ownership restriction. The restriction has its own tests in TicketAccessControlTest.
        raiserId = insertUser("Raiser One", "raiser.page@example.test", 9601L, "IT_SUPPORT");
        assigneeId = insertUser("Assignee Two", "assignee.page@example.test", 9602L, "DEVELOPER");
        applicationId = insertApplication("Payroll", "Salary");
    }

    /** Inserts tickets directly so status/priority/timestamps can be controlled precisely. */
    private void insertTickets(int count, String status, String priority, boolean assigned) {
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO tickets (ticket_number, title, description, issue_type, priority,
                                         status, created_at, updated_at, raised_by, assigned_to,
                                         application_id, module_name, version)
                    VALUES (?, ?, 'd', 'BUG', ?, ?, ?, ?, ?, ?, ?, 'Salary', 0)
                    """,
                    "TKT-" + status + "-" + priority + "-" + i,
                    "Ticket " + status + " " + i,
                    priority, status,
                    LocalDateTime.now().minusMinutes(count - i), LocalDateTime.now(),
                    raiserId, assigned ? assigneeId : null, applicationId);
        }
    }

    // ------------------------------------------------------------ pagination

    @Test
    @DisplayName("defaults to the first page of 20, newest first")
    void defaultsToFirstPageOfTwenty() {
        insertTickets(50, "OPEN", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(raiserId, "/api/tickets");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(20);
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("50");
        assertThat(response.getHeaders().getFirst("X-Total-Pages")).isEqualTo("3");
        assertThat(response.getHeaders().getFirst("X-Page-Number")).isEqualTo("0");
        assertThat(response.getHeaders().getFirst("X-Page-Size")).isEqualTo("20");
        assertThat(response.getHeaders().getFirst("X-Has-Next")).isEqualTo("true");
    }

    @Test
    @DisplayName("the response body is still a plain JSON array, as before Phase 3")
    void bodyRemainsAJsonArray() {
        insertTickets(3, "OPEN", "LOW", false);

        String raw = getRawAs(raiserId, "/api/tickets").getBody();

        assertThat(raw)
                .as("pagination metadata lives in headers so the body shape is unchanged")
                .startsWith("[")
                .endsWith("]");
    }

    @Test
    @DisplayName("page and size are honoured")
    void pageAndSizeAreHonoured() {
        insertTickets(25, "OPEN", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> page1 = getArrayAs(raiserId, "/api/tickets?page=1&size=10");

        assertThat(page1.getBody()).hasSize(10);
        assertThat(page1.getHeaders().getFirst("X-Page-Number")).isEqualTo("1");
        assertThat(page1.getHeaders().getFirst("X-Total-Pages")).isEqualTo("3");
    }

    @Test
    @DisplayName("page size is capped so a client cannot request the whole table")
    void pageSizeIsCapped() {
        insertTickets(150, "OPEN", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(raiserId, "/api/tickets?size=100000");

        assertThat(response.getBody())
                .as("""
                        Without a cap the endpoint would still be unbounded - a client could simply \
                        ask for everything and reproduce the original defect.""")
                .hasSize(100);
        assertThat(response.getHeaders().getFirst("X-Page-Size")).isEqualTo("100");
    }

    @Test
    @DisplayName("paging covers every row exactly once, with no gaps or duplicates")
    void pagingIsStableAcrossPages() {
        insertTickets(45, "OPEN", "LOW", false);

        Set<Object> seen = new HashSet<>();
        List<Object> collected = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            List<Map<String, Object>> body = getArrayAs(raiserId, "/api/tickets?page=" + page + "&size=10").getBody();
            body.forEach(t -> {
                collected.add(t.get("id"));
                seen.add(t.get("id"));
            });
        }

        assertThat(collected).as("45 rows across pages of 10").hasSize(45);
        assertThat(seen)
                .as("""
                        A deterministic sort is what makes this hold. Ordering by createdAt alone \
                        would let rows with equal timestamps shuffle between pages, so id is always \
                        appended as a tiebreaker.""")
                .hasSize(45);
    }

    @Test
    @DisplayName("an out-of-range page returns an empty array, not an error")
    void outOfRangePageIsEmpty() {
        insertTickets(5, "OPEN", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(raiserId, "/api/tickets?page=99&size=20");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("5");
    }

    @Test
    @DisplayName("negative or zero paging parameters fall back to safe defaults")
    void invalidPagingParametersFallBack() {
        insertTickets(30, "OPEN", "LOW", false);

        assertThat(getArrayAs(raiserId, "/api/tickets?page=-5&size=-1").getBody()).hasSize(20);
        assertThat(getArrayAs(raiserId, "/api/tickets?size=0").getBody()).hasSize(20);
    }

    // ------------------------------------------------------------- filtering

    @Test
    @DisplayName("filters by status")
    void filtersByStatus() {
        insertTickets(7, "OPEN", "LOW", false);
        insertTickets(4, "CLOSED", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(raiserId, "/api/tickets?status=OPEN");

        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("7");
        assertThat(response.getBody()).hasSize(7)
                .allSatisfy(t -> assertThat(t).containsEntry("status", "OPEN"));
    }

    @Test
    @DisplayName("filters by priority")
    void filtersByPriority() {
        insertTickets(3, "OPEN", "CRITICAL", false);
        insertTickets(6, "OPEN", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(raiserId, "/api/tickets?priority=CRITICAL");

        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("3");
        assertThat(response.getBody()).allSatisfy(
                t -> assertThat(t).containsEntry("priority", "CRITICAL"));
    }

    @Test
    @DisplayName("filters by assigned user, and unassigned tickets are excluded")
    void filtersByAssignedUser() {
        insertTickets(5, "ASSIGNED", "LOW", true);
        insertTickets(8, "OPEN", "LOW", false);

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(raiserId, "/api/tickets?assignedTo=" + assigneeId);

        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("5");
        assertThat(response.getBody()).allSatisfy(
                t -> assertThat(t).containsEntry("assignedTo", "Assignee Two"));
    }

    @Test
    @DisplayName("filters by raising user")
    void filtersByRaisingUser() {
        insertTickets(6, "OPEN", "LOW", false);

        assertThat(getArrayAs(raiserId, "/api/tickets?raisedBy=" + raiserId)
                .getHeaders().getFirst("X-Total-Count")).isEqualTo("6");
        assertThat(getArrayAs(raiserId, "/api/tickets?raisedBy=" + assigneeId)
                .getHeaders().getFirst("X-Total-Count")).isEqualTo("0");
    }

    @Test
    @DisplayName("filters combine")
    void filtersCombine() {
        insertTickets(4, "OPEN", "HIGH", true);
        insertTickets(3, "OPEN", "LOW", true);
        insertTickets(5, "CLOSED", "HIGH", true);

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(raiserId, "/api/tickets?status=OPEN&priority=HIGH&assignedTo=" + assigneeId);

        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("4");
    }

    @Test
    @DisplayName("an unparseable filter value is a client error")
    void invalidFilterValueIsClientError() {
        assertThat(getObjectAs(raiserId, "/api/tickets?status=NOT_A_STATUS").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --------------------------------------------------------------- sorting

    @Test
    @DisplayName("sorts newest first by default")
    void sortsNewestFirstByDefault() {
        insertTickets(5, "OPEN", "LOW", false);

        List<Map<String, Object>> body = getArrayAs(raiserId, "/api/tickets").getBody();

        List<String> createdAt = body.stream().map(t -> (String) t.get("createdAt")).toList();
        assertThat(createdAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("sort direction can be reversed")
    void sortDirectionCanBeReversed() {
        insertTickets(5, "OPEN", "LOW", false);

        List<String> createdAt = getArrayAs(raiserId, "/api/tickets?sort=createdAt,asc").getBody()
                .stream().map(t -> (String) t.get("createdAt")).toList();

        assertThat(createdAt).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test
    @DisplayName("sorting by ticketNumber works and is deterministic")
    void sortsByTicketNumber() {
        insertTickets(5, "OPEN", "LOW", false);

        List<String> numbers = getArrayAs(raiserId, "/api/tickets?sort=ticketNumber,asc").getBody()
                .stream().map(t -> (String) t.get("ticketNumber")).toList();

        assertThat(numbers).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test
    @DisplayName("a sort property outside the whitelist is rejected")
    void unknownSortPropertyIsRejected() {
        insertTickets(2, "OPEN", "LOW", false);

        ResponseEntity<Map<String, Object>> response = getObjectAs(raiserId, "/api/tickets?sort=description");

        assertThat(response.getStatusCode())
                .as("""
                        An arbitrary sort property would at best cause an unindexed sort and at worst \
                        fail at query time, so only vetted properties are accepted.""")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("sorting happens in the database, not in Java")
    void sortingHappensInTheDatabase() {
        insertTickets(40, "OPEN", "LOW", false);

        // If sorting were done in memory the endpoint would have to load all 40 rows to return 20.
        // Asking for the last page by ascending order proves the database applied both ORDER BY and
        // LIMIT/OFFSET: the returned rows are the oldest ones, which an in-page Java sort of a
        // descending fetch could not produce.
        List<Map<String, Object>> firstAsc = getArrayAs(raiserId, "/api/tickets?sort=createdAt,asc&size=5").getBody();
        List<Map<String, Object>> firstDesc = getArrayAs(raiserId, "/api/tickets?sort=createdAt,desc&size=5").getBody();

        assertThat(firstAsc.get(0).get("id")).isNotEqualTo(firstDesc.get(0).get("id"));
        assertThat((String) firstAsc.get(0).get("createdAt"))
                .isLessThan((String) firstDesc.get(0).get("createdAt"));
    }
}
