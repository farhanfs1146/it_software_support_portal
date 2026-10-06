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

/** Characterization tests for {@code /api/applications}. All behaviour here is legitimate today. */
@DatabaseIntegrationTest
class ApplicationApiCharacterizationTest extends AbstractIntegrationTest {

    private long adminId;
    private long requesterId;

    @org.junit.jupiter.api.BeforeEach
    void seedActors() {
        // Managing the catalogue needs APPLICATION_MANAGE (ADMIN); reading it needs only
        // APPLICATION_READ, which every authenticated user has.
        adminId = insertUser("App Admin", "admin.apps@example.test", 9200L, "ADMIN");
        requesterId = insertUser("App Reader", "reader.apps@example.test", 9201L, "EMPLOYEE");
    }

    private static Map<String, Object> request(String appName, String moduleName, boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", appName);
        body.put("moduleName", moduleName);
        body.put("active", active);
        return body;
    }

    @Test
    @DisplayName("creates an application")
    void createsApplication() {
        ResponseEntity<Map<String, Object>> response =
                postObjectAs(adminId, "/api/applications", request("Payroll", "Salary", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .containsEntry("appName", "Payroll")
                .containsEntry("moduleName", "Salary")
                .containsEntry("active", true);
    }

    @Test
    @DisplayName("updates an application in place")
    void updatesApplication() {
        long id = insertApplication("Old Name", "Old Module");

        ResponseEntity<Map<String, Object>> response =
                putObjectAs(adminId, "/api/applications/" + id, request("New Name", "New Module", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull()
                .containsEntry("appName", "New Name")
                .containsEntry("moduleName", "New Module");
    }

    @Test
    @DisplayName("DELETE deactivates rather than removing the row (soft delete)")
    void deleteIsSoft() {
        long id = insertApplication("Doomed", "Module");

        deleteAs(adminId, "/api/applications/" + id);

        assertThat(countRows("applications"))
                .as("soft delete must keep the row")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT active FROM applications WHERE id = ?", Boolean.class, id))
                .isFalse();
    }

    @Test
    @DisplayName("/active returns only active applications")
    void activeEndpointFiltersInactive() {
        long active = insertApplication("Active App", "M1");
        long inactive = insertApplication("Inactive App", "M2");
        jdbc.update("UPDATE applications SET active = false WHERE id = ?", inactive);

        ResponseEntity<List<Map<String, Object>>> response = getArrayAs(requesterId, "/api/applications/active");

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0)).containsEntry("id", (int) active);
    }

    @Test
    @DisplayName("lists all applications including inactive ones")
    void listsAll() {
        insertApplication("A", "M");
        long inactive = insertApplication("B", "M");
        jdbc.update("UPDATE applications SET active = false WHERE id = ?", inactive);

        assertThat(getArrayAs(requesterId, "/api/applications").getBody()).hasSize(2);
    }
}
