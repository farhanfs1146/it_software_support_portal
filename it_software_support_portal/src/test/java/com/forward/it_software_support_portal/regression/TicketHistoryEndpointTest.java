package com.forward.it_software_support_portal.regression;

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
 * {@code GET /api/tickets/{id}/history} - the endpoint that makes the audit trail readable.
 *
 * <p>The trail has been written since Phase 2 and read by nothing: no endpoint exposed it, and
 * {@code TicketHistoryTrackingRepository}'s only finder was dead code, so every create, assignment and
 * status change was recorded and then invisible. V8's index existed for a query the application never
 * ran.
 *
 * <p>These tests are an integration test rather than a unit test for one specific reason: the
 * projection query is a JPQL constructor expression with a join and a {@code Pageable} sort applied on
 * top, and none of that is exercised by mocks. Hibernate either builds that SQL or it does not, and
 * only a real database says which. {@link com.forward.it_software_support_portal.unit.TicketHistoryAccessTest}
 * covers the access-control rule without Docker.
 */
@DatabaseIntegrationTest
class TicketHistoryEndpointTest extends AbstractIntegrationTest {

    private long supportId;
    private long requesterId;
    private long strangerId;
    private long assigneeId;
    private long applicationId;

    @BeforeEach
    void seed() {
        supportId = insertUser("Support Staff", "support.hist@example.test", 9901L, "IT_SUPPORT");
        requesterId = insertUser("Raising User", "raiser.hist@example.test", 9902L, "EMPLOYEE");
        strangerId = insertUser("Unrelated User", "stranger.hist@example.test", 9903L, "EMPLOYEE");
        assigneeId = insertUser("Dev Assignee", "dev.hist@example.test", 9904L, "DEVELOPER");
        applicationId = insertApplication("Payroll", "Salary");
    }

    /** Raises a ticket, assigns it and resolves it: four audit rows in a known order. */
    private long ticketWithTrail() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Payslip export fails");
        body.put("description", "The salary module errors on export.");
        body.put("issueType", "BUG");
        body.put("priority", "HIGH");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");

        long id = ((Number) postObjectAs(requesterId, "/api/tickets", body).getBody().get("id")).longValue();
        putObjectAs(supportId, "/api/tickets/" + id + "/assign/" + assigneeId, Map.of());
        patchObjectAs(supportId, "/api/tickets/" + id + "/status?status=RESOLVED");
        return id;
    }

    @Test
    @DisplayName("returns the trail newest first, with the actor's name")
    void returnsTrailNewestFirst() {
        long ticketId = ticketWithTrail();

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(supportId, "/api/tickets/" + ticketId + "/history");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(entry -> entry.get("actionType"))
                .as("""
                        create, then the two rows assignment writes, then the status change - \
                        newest first by changed_at DESC, id DESC.""")
                .containsExactly("STATUS_CHANGED", "STATUS_CHANGED", "ASSIGNED", "CREATED");

        Map<String, Object> newest = response.getBody().get(0);
        assertThat(newest.get("fieldName")).isEqualTo("status");
        assertThat(newest.get("newValue")).isEqualTo("RESOLVED");
        assertThat(newest.get("changedBy"))
                .as("changed_by records who performed the change, not who received it")
                .isEqualTo("Support Staff");
        assertThat(newest.get("changedAt")).isNotNull();
    }

    @Test
    @DisplayName("the create entry records the raiser and the opening status")
    void createEntryIsRecorded() {
        long ticketId = ticketWithTrail();

        List<Map<String, Object>> trail =
                getArrayAs(supportId, "/api/tickets/" + ticketId + "/history").getBody();
        Map<String, Object> oldest = trail.get(trail.size() - 1);

        assertThat(oldest.get("actionType")).isEqualTo("CREATED");
        assertThat(oldest.get("oldValue")).isNull();
        assertThat(oldest.get("newValue")).isEqualTo("OPEN");
        assertThat(oldest.get("changedBy")).isEqualTo("Raising User");
    }

    @Test
    @DisplayName("carries the same pagination headers as every other listing")
    void carriesPaginationHeaders() {
        long ticketId = ticketWithTrail();

        ResponseEntity<List<Map<String, Object>>> firstPage =
                getArrayAs(supportId, "/api/tickets/" + ticketId + "/history?page=0&size=2");

        assertThat(firstPage.getBody()).hasSize(2);
        assertThat(firstPage.getHeaders().getFirst("X-Total-Count")).isEqualTo("4");
        assertThat(firstPage.getHeaders().getFirst("X-Total-Pages")).isEqualTo("2");
        assertThat(firstPage.getHeaders().getFirst("X-Has-Next")).isEqualTo("true");
    }

    @Test
    @DisplayName("oldest-first is available through the sort parameter")
    void supportsAscendingSort() {
        long ticketId = ticketWithTrail();

        List<Map<String, Object>> trail = getArrayAs(
                supportId, "/api/tickets/" + ticketId + "/history?sort=changedAt,asc").getBody();

        assertThat(trail).extracting(entry -> entry.get("actionType"))
                .containsExactly("CREATED", "ASSIGNED", "STATUS_CHANGED", "STATUS_CHANGED");
    }

    @Test
    @DisplayName("a property outside the whitelist is a 400, not a 500")
    void rejectsUnsortableProperty() {
        long ticketId = ticketWithTrail();

        assertThat(getRawAs(supportId, "/api/tickets/" + ticketId + "/history?sort=oldValue")
                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("the raiser and the assignee can both read the trail")
    void involvedPartiesCanRead() {
        long ticketId = ticketWithTrail();

        assertThat(getRawAs(requesterId, "/api/tickets/" + ticketId + "/history").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getRawAs(assigneeId, "/api/tickets/" + ticketId + "/history").getStatusCode())
                .as("being the assignee is involvement, exactly as for the ticket itself")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an uninvolved user gets the same 404 as for a ticket that does not exist")
    void uninvolvedUserGetsNotFound() {
        long ticketId = ticketWithTrail();

        assertThat(getRawAs(strangerId, "/api/tickets/" + ticketId + "/history").getStatusCode())
                .as("""
                        An empty 200 would confirm the ticket exists. The history endpoint has to \
                        answer exactly what GET /api/tickets/{id} answers, or reading the trail \
                        becomes a way around that boundary.""")
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(getRawAs(strangerId, "/api/tickets/999999/history").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("reading the trail costs two statements regardless of how long it is")
    void readingTheTrailDoesNotScaleWithItsLength() {
        long ticketId = ticketWithTrail();
        for (String next : new String[]{"REOPENED", "IN_PROGRESS", "RESOLVED", "CLOSED"}) {
            patchObjectAs(supportId, "/api/tickets/" + ticketId + "/status?status=" + next);
        }

        List<String> sql = capturingSql(
                () -> getArrayAs(supportId, "/api/tickets/" + ticketId + "/history"));

        List<String> trailQueries = sql.stream()
                .map(String::toLowerCase)
                .filter(statement -> statement.contains("ticket_history_tracking"))
                .toList();

        assertThat(trailQueries)
                .as("""
                        The projection exists so this stays flat: one page query and one count, for a \
                        trail of any length. TicketHistoryTracking has two EAGER @ManyToOne \
                        associations, so reading entities here would issue a select per distinct \
                        ticket and user - audit finding P1-1 reproduced on a new endpoint.""")
                .hasSize(2);
    }
}
