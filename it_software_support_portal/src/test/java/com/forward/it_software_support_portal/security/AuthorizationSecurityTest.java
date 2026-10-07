package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authorization: who may do what, and the 403 boundary.
 *
 * <p>Checks the role-to-permission mapping through real endpoints rather than by inspecting
 * {@link RolePermissions} directly, so a mapping that is correct in isolation but wired up wrongly still
 * fails here.
 */
@DatabaseIntegrationTest
class AuthorizationSecurityTest extends AbstractIntegrationTest {

    private long employeeId;
    private long managerId;
    private long supportId;
    private long developerId;
    private long adminId;
    private long applicationId;

    @BeforeEach
    void seed() {
        employeeId = insertUser("Emma Employee", "emma@example.test", 8101L, "EMPLOYEE");
        managerId = insertUser("Mark Manager", "mark@example.test", 8102L, "MANAGER");
        supportId = insertUser("Sam Support", "sam@example.test", 8103L, "IT_SUPPORT");
        developerId = insertUser("Dev Dave", "dave@example.test", 8104L, "DEVELOPER");
        adminId = insertUser("Ada Admin", "ada@example.test", 8105L, "ADMIN");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> ticketBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Authorization probe");
        body.put("description", "d");
        body.put("issueType", "BUG");
        body.put("priority", "LOW");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    private long ticketRaisedBy(long raiser) {
        return ((Number) postObjectAs(raiser, "/api/tickets", ticketBody()).getBody().get("id"))
                .longValue();
    }

    private Map<String, Object> userBody(long code, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeCode", code);
        body.put("fullName", "Created User");
        body.put("email", email);
        body.put("role", "EMPLOYEE");
        body.put("active", true);
        body.put("password", "a-sufficiently-long-password");
        return body;
    }

    // ------------------------------------------------- ticket creation: everyone

    @Test
    @DisplayName("every role may raise a ticket - that is the point of a support portal")
    void everyRoleMayRaiseATicket() {
        for (long actor : new long[]{employeeId, managerId, supportId, developerId, adminId}) {
            assertThat(postObjectAs(actor, "/api/tickets", ticketBody()).getStatusCode())
                    .as("user %d must be able to raise a ticket", actor)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // --------------------------------------------------------- assignment

    @Test
    @DisplayName("support staff and admins may assign; requesters may not")
    void onlySupportMayAssign() {
        long ticketId = ticketRaisedBy(employeeId);

        assertThat(putObjectAs(employeeId, "/api/tickets/" + ticketId + "/assign/" + supportId, Map.of())
                .getStatusCode())
                .as("an EMPLOYEE has no TICKET_ASSIGN authority")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(putObjectAs(managerId, "/api/tickets/" + ticketId + "/assign/" + supportId, Map.of())
                .getStatusCode())
                .as("""
                        MANAGER is deliberately mapped the same as EMPLOYEE. Granting managers broad \
                        ticket powers on the strength of the role name would be inventing a business \
                        rule - departments do not exist in this schema. See docs/SECURITY.md.""")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(putObjectAs(supportId, "/api/tickets/" + ticketId + "/assign/" + developerId, Map.of())
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(putObjectAs(adminId, "/api/tickets/" + ticketId + "/assign/" + supportId, Map.of())
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------ status changes

    @Test
    @DisplayName("support staff may change status; requesters may not, even on their own ticket")
    void onlySupportMayChangeStatus() {
        long ownTicket = ticketRaisedBy(employeeId);

        assertThat(patchObjectAs(employeeId, "/api/tickets/" + ownTicket + "/status?status=CLOSED")
                .getStatusCode())
                .as("""
                        Whether a requester may close their own ticket is an undecided business rule, so \
                        the permission is withheld rather than guessed at.""")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(patchObjectAs(supportId, "/api/tickets/" + ownTicket + "/status?status=IN_PROGRESS")
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------- user admin

    @Test
    @DisplayName("only admins may create users")
    void onlyAdminsMayCreateUsers() {
        assertThat(postObjectAs(employeeId, "/api/users", userBody(8201L, "a@example.test"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postObjectAs(supportId, "/api/users", userBody(8202L, "b@example.test"))
                .getStatusCode())
                .as("support staff may read users but not create them")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postObjectAs(adminId, "/api/users", userBody(8203L, "c@example.test"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("support staff and admins may list users; requesters may not")
    void onlySupportMayListUsers() {
        assertThat(getRawAs(employeeId, "/api/users").getStatusCode())
                .as("""
                        This is the audit's anonymous-PII-dump finding, now closed: listing users exposes \
                        names and email addresses, so it needs USER_READ.""")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getRawAs(managerId, "/api/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getRawAs(supportId, "/api/users").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRawAs(adminId, "/api/users").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --------------------------------------------------- application admin

    @Test
    @DisplayName("every authenticated user may read the application catalogue")
    void everyoneMayReadApplications() {
        for (long actor : new long[]{employeeId, managerId, supportId, adminId}) {
            assertThat(getRawAs(actor, "/api/applications").getStatusCode())
                    .as("raising a ticket requires choosing an application, so reading is open")
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("only admins may modify the application catalogue")
    void onlyAdminsMayManageApplications() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", "New App");
        body.put("moduleName", "Module");
        body.put("active", true);

        assertThat(postObjectAs(employeeId, "/api/applications", body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postObjectAs(supportId, "/api/applications", body).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postObjectAs(adminId, "/api/applications", body).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(deleteAs(employeeId, "/api/applications/" + applicationId).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deleteAs(adminId, "/api/applications/" + applicationId).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------- response shape

    @Test
    @DisplayName("403 responses are RFC 7807 and name no permission or role")
    void forbiddenResponseShape() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(tokenFor(employeeId));
        org.springframework.http.ResponseEntity<String> response = rest.exchange(
                "/api/users", org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull()
                .contains("\"status\":403")
                .contains("\"title\"");
        assertThat(response.getBody())
                .as("naming the missing authority would map the authorization model for an attacker")
                .doesNotContain("USER_READ")
                .doesNotContain("EMPLOYEE")
                .doesNotContain("hasAuthority");
    }

    @Test
    @DisplayName("an authorization denial is 403, not 500 - the advice must map AccessDeniedException")
    void denialIsNotAnInternalError() {
        assertThat(getRawAs(employeeId, "/api/users").getStatusCode())
                .as("""
                        @PreAuthorize throws inside the dispatcher, so GlobalExceptionHandler sees it \
                        before Spring Security's translation filter would. Without an explicit mapping \
                        its catch-all would turn every denial into a 500.""")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a role with no mapped permissions can authenticate but do nothing")
    void unmappedRoleGrantsNothing() {
        // HOD is mapped as a requester; this asserts the floor rather than a gap. The real guarantee is
        // that RolePermissions.of returns an empty set for anything unmapped, so a role added to the
        // enum without a mapping grants nothing instead of everything.
        assertThat(RolePermissions.of(null)).isEmpty();
        long hodId = insertUser("Hilda HOD", "hilda@example.test", 8106L, "HOD");
        assertThat(getRawAs(hodId, "/api/users").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postObjectAs(hodId, "/api/tickets", ticketBody()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
