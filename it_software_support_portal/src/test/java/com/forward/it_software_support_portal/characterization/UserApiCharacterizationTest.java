package com.forward.it_software_support_portal.characterization;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@code /api/users}.
 *
 * <p>These describe behaviour that is legitimate today and is expected to keep working. They are a
 * safety net for later phases, not a description of defects.
 */
@DatabaseIntegrationTest
class UserApiCharacterizationTest extends AbstractIntegrationTest {

    private long adminId;

    @org.junit.jupiter.api.BeforeEach
    void seedAdmin() {
        // User administration requires USER_MANAGE; only ADMIN holds it.
        adminId = insertUser("User Admin", "admin.users@example.test", 9100L, "ADMIN");
    }

    private static Map<String, Object> newUserRequest(long employeeCode, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeCode", employeeCode);
        body.put("fullName", "Test User");
        body.put("email", email);
        body.put("departmentId", 10);
        body.put("designationId", 20);
        body.put("role", "EMPLOYEE");
        body.put("active", true);
        // Phase 4: an initial password is required, and is stored only as a BCrypt hash.
        body.put("password", "initial-password-123");
        return body;
    }

    @Test
    @DisplayName("creates a user and echoes the stored representation")
    void createsUser() {
        ResponseEntity<Map<String, Object>> response =
                postObjectAs(adminId, "/api/users", newUserRequest(5001L, "new.user@example.test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .containsEntry("employeeCode", 5001)
                .containsEntry("fullName", "Test User")
                .containsEntry("email", "new.user@example.test")
                .containsEntry("role", "EMPLOYEE")
                .containsEntry("active", true);
        assertThat(response.getBody().get("id")).isNotNull();

        assertThat(countRows("users")).isEqualTo(2); // created user + seeded admin
    }

    @Test
    @DisplayName("rejects a duplicate employee code")
    void rejectsDuplicateEmployeeCode() {
        postObjectAs(adminId, "/api/users", newUserRequest(5002L, "first@example.test"));

        ResponseEntity<Map<String, Object>> duplicate =
                postObjectAs(adminId, "/api/users", newUserRequest(5002L, "second@example.test"));

        assertThat(duplicate.getStatusCode().isError())
                .as("duplicate employee code must not be accepted")
                .isTrue();
        assertThat(countRows("users")).isEqualTo(2); // created user + seeded admin
    }

    @Test
    @DisplayName("rejects a duplicate email")
    void rejectsDuplicateEmail() {
        postObjectAs(adminId, "/api/users", newUserRequest(5003L, "dupe@example.test"));

        ResponseEntity<Map<String, Object>> duplicate =
                postObjectAs(adminId, "/api/users", newUserRequest(5004L, "dupe@example.test"));

        assertThat(duplicate.getStatusCode().isError())
                .as("duplicate email must not be accepted")
                .isTrue();
        assertThat(countRows("users")).isEqualTo(2); // created user + seeded admin
    }

    @Test
    @DisplayName("returns a user by id")
    void getsUserById() {
        long id = insertUser("Alice Example", "alice@example.test", 7001L, "ADMIN");

        ResponseEntity<Map<String, Object>> response = getObjectAs(adminId, "/api/users/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .containsEntry("fullName", "Alice Example")
                .containsEntry("role", "ADMIN");
    }

    @Test
    @DisplayName("lists all users")
    void listsAllUsers() {
        insertUser("One", "one@example.test", 7101L, "EMPLOYEE");
        insertUser("Two", "two@example.test", 7102L, "DEVELOPER");

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(adminId, "/api/users");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3); // two inserted + seeded admin
    }

    @Test
    @DisplayName("FIXED (P2-1): the user list is bounded and honours pagination parameters")
    void userListIsPaginated() {
        for (int i = 0; i < 25; i++) {
            insertUser("User " + i, "bulk" + i + "@example.test", 7200L + i, "EMPLOYEE");
        }

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(adminId, "/api/users?page=0&size=5");

        assertThat(response.getBody())
                .as("""
                        Before Phase 6 these parameters were ignored and all 26 rows came back - the audit \
                        measured 787 KB for 5,103 users.""")
                .hasSize(5);
        assertThat(response.getHeaders().getFirst("X-Total-Count"))
                .as("the true total still reaches the client, from a header")
                .isEqualTo("26");
    }
}
