package com.forward.it_software_support_portal.characterization;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pagination for {@code /api/users}, {@code /api/applications} and {@code /api/applications/active}
 * (Phase 6).
 *
 * <p>These were the last unbounded collection endpoints: the same defect Phase 3 fixed for tickets, left
 * open through Phase 4 and 5 because authentication and permission-gating had removed the *disclosure*
 * problem without removing the *unboundedness*. The contract matches tickets exactly - a JSON array body
 * with pagination metadata in headers - so a client that already handles ticket paging needs no new
 * machinery.
 */
@DatabaseIntegrationTest
class UserAndApplicationPaginationTest extends AbstractIntegrationTest {

    private long adminId;

    @BeforeEach
    void seedAdmin() {
        // USER_READ for the user listing, APPLICATION_READ for the catalogue: ADMIN holds both.
        adminId = insertUser("Zulu Admin", "zulu.admin@example.test", 7900L, "ADMIN");
    }

    private void insertUsers(int count) {
        for (int i = 0; i < count; i++) {
            insertUser("Person %02d".formatted(i), "person%02d@example.test".formatted(i),
                    7000L + i, "EMPLOYEE");
        }
    }

    private void insertApplications(int count) {
        for (int i = 0; i < count; i++) {
            insertApplication("App %02d".formatted(i), "Module " + i);
        }
    }

    // ------------------------------------------------------------- /api/users

    @Test
    @DisplayName("users: defaults to the first page of 20, name-ordered")
    void usersDefaultPage() {
        insertUsers(30);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(adminId, "/api/users");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(20);
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("31");
        assertThat(response.getHeaders().getFirst("X-Total-Pages")).isEqualTo("2");
        assertThat(response.getHeaders().getFirst("X-Page-Number")).isEqualTo("0");
        assertThat(response.getHeaders().getFirst("X-Page-Size")).isEqualTo("20");
        assertThat(response.getHeaders().getFirst("X-Has-Next")).isEqualTo("true");

        List<String> names = response.getBody().stream()
                .map(user -> (String) user.get("fullName")).toList();
        assertThat(names)
                .as("a directory is browsed alphabetically, so that is the default order")
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    @DisplayName("users: the body is still a plain JSON array")
    void usersBodyRemainsAnArray() {
        insertUsers(3);

        String raw = getRawAs(adminId, "/api/users").getBody();

        assertThat(raw)
                .as("metadata went into headers specifically so the body shape did not change")
                .startsWith("[")
                .endsWith("]");
    }

    @Test
    @DisplayName("users: page and size are honoured")
    void usersPageAndSize() {
        insertUsers(24);

        ResponseEntity<List<Map<String, Object>>> page2 = getArrayAs(adminId, "/api/users?page=2&size=10");

        assertThat(page2.getBody()).hasSize(5); // 25 total, pages of 10 -> last page holds 5
        assertThat(page2.getHeaders().getFirst("X-Total-Pages")).isEqualTo("3");
        assertThat(page2.getHeaders().getFirst("X-Has-Next")).isEqualTo("false");
    }

    @Test
    @DisplayName("users: page size is capped so a client cannot ask for the whole table")
    void usersPageSizeIsCapped() {
        insertUsers(150);

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(adminId, "/api/users?size=100000");

        assertThat(response.getBody())
                .as("without a cap the endpoint would still be unbounded on request")
                .hasSize(100);
        assertThat(response.getHeaders().getFirst("X-Page-Size")).isEqualTo("100");
    }

    @Test
    @DisplayName("users: paging covers every row exactly once")
    void usersPagingIsStable() {
        insertUsers(44); // 45 including the admin

        Set<Object> seen = new HashSet<>();
        List<Object> collected = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            getArrayAs(adminId, "/api/users?page=" + page + "&size=10").getBody()
                    .forEach(user -> {
                        collected.add(user.get("id"));
                        seen.add(user.get("id"));
                    });
        }

        assertThat(collected).hasSize(45);
        assertThat(seen)
                .as("""
                        Names are not unique, so ordering by fullName alone would let rows shuffle between \
                        pages. id is always appended as a tiebreaker.""")
                .hasSize(45);
    }

    @Test
    @DisplayName("users: sortable properties work; anything else is rejected")
    void usersSorting() {
        insertUsers(5);

        List<String> byEmail = getArrayAs(adminId, "/api/users?sort=email,asc").getBody()
                .stream().map(u -> (String) u.get("email")).toList();
        assertThat(byEmail).isSortedAccordingTo(Comparator.naturalOrder());

        List<String> byNameDesc = getArrayAs(adminId, "/api/users?sort=fullName,desc").getBody()
                .stream().map(u -> (String) u.get("fullName")).toList();
        assertThat(byNameDesc).isSortedAccordingTo(Comparator.reverseOrder());

        assertThat(getRawAs(adminId, "/api/users?sort=passwordHash").getStatusCode())
                .as("""
                        The whitelist is not decoration: without it a caller could name any entity \
                        property, and password_hash is a property of the entity.""")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getRawAs(adminId, "/api/users?sort=nonsense").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("users: an out-of-range page is an empty array, not an error")
    void usersOutOfRangePage() {
        insertUsers(3);

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(adminId, "/api/users?page=99&size=20");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("4");
    }

    @Test
    @DisplayName("users: no response ever contains a password hash")
    void usersNeverExposeHashes() {
        insertUserWithPassword("Hashed Person", "hashed.p6@example.test", 7950L,
                "EMPLOYEE", "a-sufficiently-long-password");

        String body = getRawAs(adminId, "/api/users").getBody();

        assertThat(body.toLowerCase())
                .as("""
                        The listing is built from a projection that does not select password_hash at all, \
                        so there is no hash in memory to leak, let alone in the response.""")
                .doesNotContain("password")
                .doesNotContain("$2a$");
    }

    // ------------------------------------------------------ /api/applications

    @Test
    @DisplayName("applications: defaults to the first page of 20, name-ordered")
    void applicationsDefaultPage() {
        insertApplications(25);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(adminId, "/api/applications");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(20);
        assertThat(response.getHeaders().getFirst("X-Total-Count")).isEqualTo("25");

        List<String> names = response.getBody().stream()
                .map(app -> (String) app.get("appName")).toList();
        assertThat(names).isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    @DisplayName("applications: page size is capped")
    void applicationsPageSizeIsCapped() {
        insertApplications(130);

        assertThat(getArrayAs(adminId, "/api/applications?size=5000").getBody()).hasSize(100);
    }

    @Test
    @DisplayName("applications: an unknown sort property is rejected")
    void applicationsUnknownSortRejected() {
        insertApplications(2);

        assertThat(getRawAs(adminId, "/api/applications?sort=whatever").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ----------------------------------------------- /api/applications/active

    @Test
    @DisplayName("active applications: paginated, and still filtered to active only")
    void activeApplicationsArePaginatedAndFiltered() {
        insertApplications(30);
        // Deactivate a third of them.
        jdbc.update("UPDATE applications SET active = false WHERE id % 3 = 0");
        int activeCount = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE active = true", Integer.class);

        ResponseEntity<List<Map<String, Object>>> response =
                getArrayAs(adminId, "/api/applications/active");

        assertThat(response.getBody()).hasSize(Math.min(20, activeCount));
        assertThat(response.getHeaders().getFirst("X-Total-Count"))
                .as("""
                        The count must reflect the filter, not the whole table - otherwise a client paging \
                        through "active" would be told there are more pages than exist.""")
                .isEqualTo(String.valueOf(activeCount));
        assertThat(response.getBody()).allSatisfy(
                app -> assertThat(app).containsEntry("active", true));
    }

    @Test
    @DisplayName("active applications: paging covers every active row exactly once")
    void activePagingIsStable() {
        insertApplications(30);
        jdbc.update("UPDATE applications SET active = false WHERE id % 3 = 0");
        int activeCount = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE active = true", Integer.class);

        Set<Object> seen = new HashSet<>();
        for (int page = 0; page * 7 < activeCount; page++) {
            getArrayAs(adminId, "/api/applications/active?page=" + page + "&size=7").getBody()
                    .forEach(app -> seen.add(app.get("id")));
        }

        assertThat(seen).hasSize(activeCount);
    }

    // --------------------------------------------------- authorization intact

    @Test
    @DisplayName("pagination did not loosen authorization")
    void authorizationStillApplies() {
        long employeeId = insertUser("Emma Employee", "emma.p6@example.test", 7960L, "EMPLOYEE");

        assertThat(getRaw("/api/users").getStatusCode())
                .as("unauthenticated is still 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getRawAs(employeeId, "/api/users").getStatusCode())
                .as("""
                        A requester still has no USER_READ. Adding paging must not have turned the \
                        permission check into a page-size check.""")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getRawAs(employeeId, "/api/applications").getStatusCode())
                .as("but every authenticated user may still read the catalogue")
                .isEqualTo(HttpStatus.OK);
    }
}
