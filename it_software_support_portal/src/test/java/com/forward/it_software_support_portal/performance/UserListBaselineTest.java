package com.forward.it_software_support_portal.performance;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The always-on guard that {@code /api/users} and {@code /api/applications} stay cheap (Phase 6).
 *
 * <p>The ticket list needed Phase 3 to defeat an N+1: every row pulled its raiser and its application
 * with separate selects. Neither {@code User} nor {@code Application} has a single association, so
 * there was never an N+1 here - the defect was purely that the response was unbounded. These tests
 * therefore assert the two properties that matter now: the statement count does not grow with the
 * table, and the user listing does not read the password hash column.
 *
 * <p>Like the ticket baseline, statement counts come from Hibernate {@code Statistics} and SQL text
 * from a JDBC-level inspector, so neither assertion can be satisfied by changing logging.
 */
@DatabaseIntegrationTest
class UserListBaselineTest extends AbstractIntegrationTest {

    /** The page query plus the count query behind the pagination headers, with slack for framework reads. */
    private static final int MAX_STATEMENTS_PER_PAGE = 6;

    /** An authorization header built outside any SQL-recording window. */
    private HttpEntity<Void> bearer(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenFor(userId));
        return new HttpEntity<>(headers);
    }

    private long admin() {
        return insertUser("Baseline Admin", "baseline.admin@example.test", 8800L, "ADMIN");
    }

    private void seedUsers(int count, int offset) {
        for (int i = 0; i < count; i++) {
            insertUser("Seed %05d".formatted(offset + i),
                    "seed%05d@example.test".formatted(offset + i), 100_000L + offset + i, "EMPLOYEE");
        }
    }

    private long statementsForGet(long actorId, String path) {
        // One warm request first: the very first call to an endpoint also prepares framework-level
        // statements, which would otherwise be attributed to the page query.
        getArrayAs(actorId, path);
        long before = preparedStatementCount();
        getArrayAs(actorId, path);
        return preparedStatementCount() - before;
    }

    @Test
    @DisplayName("a page of users costs a small constant number of SQL statements")
    void userPageCostsConstantStatements() {
        long adminId = admin();
        seedUsers(50, 0);
        long atFifty = statementsForGet(adminId, "/api/users");

        seedUsers(450, 50);
        long atFiveHundred = statementsForGet(adminId, "/api/users");

        System.out.printf("""
                [P2-1 AFTER] statements per /api/users page
                   51 users -> %d statements
                  501 users -> %d statements
                %n""", atFifty, atFiveHundred);

        assertThat(atFiveHundred)
                .as("""
                        A page must cost the same whether the table holds 50 rows or 500. The count \
                        query is the only reason it is two rather than one.""")
                .isEqualTo(atFifty)
                .isLessThanOrEqualTo(MAX_STATEMENTS_PER_PAGE);
    }

    @Test
    @DisplayName("a page of applications costs a small constant number of SQL statements")
    void applicationPageCostsConstantStatements() {
        long adminId = admin();
        for (int i = 0; i < 50; i++) {
            insertApplication("Seed App %04d".formatted(i), "Module " + i);
        }
        long atFifty = statementsForGet(adminId, "/api/applications");

        for (int i = 50; i < 500; i++) {
            insertApplication("Seed App %04d".formatted(i), "Module " + i);
        }
        long atFiveHundred = statementsForGet(adminId, "/api/applications");

        System.out.printf("[P2-1 AFTER] /api/applications: 50 rows -> %d statements | 500 rows -> %d%n%n",
                atFifty, atFiveHundred);

        assertThat(atFiveHundred).isEqualTo(atFifty).isLessThanOrEqualTo(MAX_STATEMENTS_PER_PAGE);
    }

    @Test
    @DisplayName("the user listing query does not read password_hash at all")
    void userListingDoesNotSelectPasswordHash() {
        long adminId = admin();
        insertUserWithPassword("Secret Holder", "secret.holder@example.test", 8801L,
                "EMPLOYEE", "a-sufficiently-long-password");

        // The token is minted BEFORE recording starts. tokenFor() loads the User entity to read its
        // role, and that load legitimately includes password_hash - it is test-harness SQL, not the
        // endpoint's, and leaving it inside the window would make this test fail for the wrong reason.
        HttpEntity<Void> auth = bearer(adminId);

        List<String> sql = capturingSql(
                () -> rest.exchange("/api/users", HttpMethod.GET, auth, String.class));

        List<String> usersQueries = sql.stream()
                .map(String::toLowerCase)
                .filter(statement -> statement.contains("from users"))
                .toList();

        assertThat(usersQueries)
                .as("the listing must have queried the users table, or this test proves nothing")
                .isNotEmpty();

        // Asserted across EVERY statement that touches users, not just the listing. That is the
        // stronger property and the one that actually matters: it does not matter which query would
        // have pulled the hash into memory.
        assertThat(usersQueries)
                .as("""
                        THE POINT OF UserRow: the hash is never fetched, so there is no hash in memory \
                        to leak through a logger, a debugger, a serialization change or a future DTO \
                        field. Defending the response body alone would leave all of those open.""")
                .allSatisfy(statement -> assertThat(statement).doesNotContain("password_hash"));

        assertThat(usersQueries)
                .filteredOn(statement -> statement.contains("employee_code"))
                .as("exactly one query produces the page itself")
                .hasSize(1);

        // Phase 7 added a second statement against users on the FIRST authenticated request per user
        // per cache TTL: the access-token revocation check. It is pinned here rather than merely
        // tolerated, because an unbounded check on this path is exactly the regression that would
        // undo Phase 3's work - and because it must never be the query that reads the hash.
        assertThat(usersQueries)
                .filteredOn(statement -> statement.contains("token_version"))
                .as("""
                        The revocation check reads two columns and nothing else. It is also cached, so \
                        subsequent requests from the same user issue no statement at all - see \
                        docs/SECURITY.md 6.5. Set app.security.session.state-cache-ttl=0 and this \
                        becomes one select per request by design.""")
                .hasSizeLessThanOrEqualTo(1)
                .allSatisfy(statement -> assertThat(statement)
                        .doesNotContain("full_name")
                        .doesNotContain("email"));
    }

    @Test
    @DisplayName("the revocation check is cached, so a repeat request costs no extra statement")
    void revocationCheckIsNotPaidOnEveryRequest() {
        long adminId = admin();
        HttpEntity<Void> auth = bearer(adminId);

        // The first request populates the cache; the statement counted here is the steady state.
        rest.exchange("/api/users", HttpMethod.GET, auth, String.class);

        List<String> sql = capturingSql(
                () -> rest.exchange("/api/users", HttpMethod.GET, auth, String.class));

        assertThat(sql.stream().filter(s -> s.toLowerCase().contains("token_version")).toList())
                .as("""
                        Phase 3 measured two statements per page at any size and Phase 5 preserved it. \
                        Checking revocation with a query per request would have broken that; the \
                        short-TTL cache plus write-through invalidation is what keeps the steady state \
                        free while a local revocation still takes effect immediately.""")
                .isEmpty();
    }

    @Test
    @DisplayName("authenticating still reads password_hash - the projection did not break login")
    void authenticationStillReadsTheHash() {
        insertUserWithPassword("Login User", "login.baseline@example.test", 8802L,
                "EMPLOYEE", "a-sufficiently-long-password");

        List<String> sql = capturingSql(() -> postObject("/api/auth/login", Map.of(
                "email", "login.baseline@example.test",
                "password", "a-sufficiently-long-password")));

        assertThat(sql)
                .as("""
                        The inverse check. A projection that excluded the hash everywhere would be \
                        secure and useless: password verification has to read it.""")
                .anySatisfy(statement ->
                        assertThat(statement.toLowerCase()).contains("password_hash"));
    }
}
