package com.forward.it_software_support_portal.characterization;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests proving bean validation is actually wired up.
 *
 * <p>These matter beyond the obvious: hibernate-validator previously reached the classpath only
 * transitively through springdoc, so {@code @Valid} worked by accident. Phase 1 declares
 * {@code spring-boot-starter-validation} explicitly, and these tests fail loudly if validation ever
 * silently disappears again (audit finding P1-9).
 */
@DatabaseIntegrationTest
class ValidationCharacterizationTest extends AbstractIntegrationTest {

    private long applicationId;
    private long requesterId;
    private long adminId;

    @BeforeEach
    void seed() {
        // Validation runs after authentication, so these tests must authenticate before they can
        // reach a validator at all. The roles are the minimum each endpoint requires.
        requesterId = insertUser("Raiser", "raiser.val@example.test", 9501L, "EMPLOYEE");
        adminId = insertUser("Val Admin", "admin.val@example.test", 9502L, "ADMIN");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> validTicket() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "A valid title");
        body.put("description", "A valid description");
        body.put("issueType", "BUG");
        body.put("priority", "HIGH");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    @Test
    @DisplayName("a blank title is rejected with 400, not persisted")
    void blankTitleRejected() {
        Map<String, Object> body = validTicket();
        body.put("title", "");

        ResponseEntity<Map<String, Object>> response = postObjectAs(requesterId, "/api/tickets", body);

        assertThat(response.getStatusCode())
                .as("@NotBlank on CreateTicketRequest.title must be enforced")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("tickets")).isZero();
    }

    @Test
    @DisplayName("a missing issueType is rejected with 400")
    void missingIssueTypeRejected() {
        Map<String, Object> body = new HashMap<>(validTicket());
        body.remove("issueType");

        assertThat(postObjectAs(requesterId, "/api/tickets", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a missing applicationId is rejected with 400")
    void missingApplicationIdRejected() {
        Map<String, Object> body = new HashMap<>(validTicket());
        body.remove("applicationId");

        assertThat(postObjectAs(requesterId, "/api/tickets", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a blank moduleName is rejected with 400")
    void blankModuleNameRejected() {
        Map<String, Object> body = validTicket();
        body.put("moduleName", "   ");

        assertThat(postObjectAs(requesterId, "/api/tickets", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an invalid email is rejected with 400")
    void invalidEmailRejected() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeCode", 9999L);
        body.put("fullName", "Bad Email");
        body.put("email", "not-an-email");
        body.put("role", "EMPLOYEE");
        body.put("active", true);

        assertThat(postObjectAs(adminId, "/api/users", body).getStatusCode())
                .as("@Email on CreateUserRequest.email must be enforced")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("users")).isEqualTo(2); // only the seeded requester and admin
    }

    @Test
    @DisplayName("a blank application name is rejected with 400")
    void blankApplicationNameRejected() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", "");
        body.put("moduleName", "Module");
        body.put("active", true);

        assertThat(postObjectAs(adminId, "/api/applications", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an unparseable enum value is rejected with 400")
    void invalidEnumRejected() {
        Map<String, Object> body = validTicket();
        body.put("priority", "NOT_A_PRIORITY");

        assertThat(postObjectAs(requesterId, "/api/tickets", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
