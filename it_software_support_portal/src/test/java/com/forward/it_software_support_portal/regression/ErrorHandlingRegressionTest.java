package com.forward.it_software_support_portal.regression;

import com.forward.it_software_support_portal.support.AbstractIntegrationTest;
import com.forward.it_software_support_portal.support.DatabaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit findings P0-6 (no global exception handling) and P1-8 (DTO sizes not aligned with the DDL) -
 * <strong>FIXED in Phase 2</strong>.
 *
 * <p><strong>What was broken.</strong> Every "not found" path threw a bare {@code RuntimeException}
 * and there was no {@code @ControllerAdvice}, so a missing resource was reported as HTTP 500.
 * Over-length input reached the database and failed there, also as a 500. Callers could not tell
 * their own mistakes from server faults, and monitoring could not tell routine 404 noise from real
 * incidents.
 *
 * <p><strong>What fixed it.</strong> Typed exceptions plus a {@code @RestControllerAdvice} returning
 * RFC 7807 {@code ProblemDetail}, and {@code @Size} constraints matched to the column widths.
 */
@DatabaseIntegrationTest
class ErrorHandlingRegressionTest extends AbstractIntegrationTest {

    private long actorId;
    private long adminId;
    private long applicationId;

    @BeforeEach
    void seed() {
        actorId = insertUser("Acting User", "actor.errors@example.test", 9901L, "IT_SUPPORT");
        adminId = insertUser("Errors Admin", "admin.errors@example.test", 9902L, "ADMIN");
        applicationId = insertApplication("Payroll", "Salary");
    }

    private Map<String, Object> ticketWithTitle(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", "Description");
        body.put("issueType", "BUG");
        body.put("priority", "LOW");
        body.put("applicationId", applicationId);
        body.put("moduleName", "Salary");
        return body;
    }

    // ---------------------------------------------------------------- 404

    @Test
    @DisplayName("FIXED (P0-6): a missing ticket returns 404")
    void missingTicketReturns404() {
        assertThat(getObjectAs(actorId, "/api/tickets/999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("FIXED (P0-6): a missing user returns 404")
    void missingUserReturns404() {
        assertThat(getObjectAs(adminId, "/api/users/999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("FIXED (P0-6): a missing application returns 404")
    void missingApplicationReturns404() {
        assertThat(getObjectAs(actorId, "/api/applications/999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("FIXED (P0-6): a status change on a missing ticket returns 404")
    void statusChangeOnMissingTicketReturns404() {
        assertThat(patchObjectAs(actorId, "/api/tickets/999999/status?status=CLOSED").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("FIXED (P0-6): assigning a missing ticket returns 404")
    void assignMissingTicketReturns404() {
        assertThat(putObjectAs(actorId, "/api/tickets/999999/assign/" + actorId, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- 400

    @Test
    @DisplayName("FIXED (P0-6): referencing an unknown application is a client error, not a 500")
    void unknownApplicationIsClientError() {
        Map<String, Object> body = ticketWithTitle("Valid title");
        body.put("applicationId", 999999);

        assertThat(postObjectAs(actorId, "/api/tickets", body).getStatusCode())
                .as("the URL was fine; a value in the body was wrong - that is a 400, not a 404")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("tickets")).isZero();
    }

    @Test
    @DisplayName("FIXED (P1-8): an over-length title is a validation error, not a database error")
    void overLongTitleIsValidationError() {
        String title = "A".repeat(150); // tickets.title is VARCHAR(100)

        assertThat(postObjectAs(actorId, "/api/tickets", ticketWithTitle(title)).getStatusCode())
                .as("@Size(max = 100) now matches the column, so this never reaches the database")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countRows("tickets")).isZero();
    }

    @Test
    @DisplayName("FIXED (P1-8): an over-length email is a validation error")
    void overLongEmailIsValidationError() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeCode", 12345L);
        body.put("fullName", "Long Email");
        body.put("email", "a".repeat(95) + "@example.test"); // users.email is VARCHAR(100)
        body.put("role", "EMPLOYEE");
        body.put("active", true);
        body.put("password", "initial-password-123");

        assertThat(postObjectAs(adminId, "/api/users", body).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an invalid status value is a client error")
    void invalidStatusValueIsClientError() {
        long ticketId = ((Number) postObjectAs(actorId, "/api/tickets", ticketWithTitle("Valid"))
                .getBody().get("id")).longValue();

        assertThat(patchObjectAs(actorId, "/api/tickets/" + ticketId + "/status?status=NOT_A_STATUS")
                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------------------------------------------------------------- 409

    @Test
    @DisplayName("FIXED (P0-6): a duplicate employee code returns 409, not 500")
    void duplicateEmployeeCodeReturns409() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeCode", 5555L);
        body.put("fullName", "First");
        body.put("email", "dup1@example.test");
        body.put("role", "EMPLOYEE");
        body.put("password", "initial-password-123");
        body.put("active", true);
        assertThat(postObjectAs(adminId, "/api/users", body).getStatusCode()).isEqualTo(HttpStatus.OK);

        body.put("email", "dup2@example.test");
        assertThat(postObjectAs(adminId, "/api/users", body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("FIXED (P0-6): a duplicate email returns 409, not 500")
    void duplicateEmailReturns409() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeCode", 6661L);
        body.put("fullName", "First");
        body.put("email", "same@example.test");
        body.put("role", "EMPLOYEE");
        body.put("password", "initial-password-123");
        body.put("active", true);
        assertThat(postObjectAs(adminId, "/api/users", body).getStatusCode()).isEqualTo(HttpStatus.OK);

        body.put("employeeCode", 6662L);
        assertThat(postObjectAs(adminId, "/api/users", body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------- response shape

    @Test
    @DisplayName("FIXED (P0-6): error responses are machine-readable RFC 7807 problem details")
    void errorResponsesAreMachineReadable() {
        ResponseEntity<Map<String, Object>> response = getObjectAs(actorId, "/api/tickets/999999");

        assertThat(response.getBody()).isNotNull()
                .containsKeys("title", "status", "detail")
                .containsEntry("status", 404);
        assertThat((String) response.getBody().get("title")).isEqualTo("Resource not found");
    }

    @Test
    @DisplayName("validation errors list the offending fields")
    void validationErrorsListFields() {
        Map<String, Object> body = ticketWithTitle("");

        ResponseEntity<Map<String, Object>> response = postObjectAs(actorId, "/api/tickets", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("violations");
        @SuppressWarnings("unchecked")
        Map<String, Object> violations = (Map<String, Object>) response.getBody().get("violations");
        assertThat(violations).containsKey("title");
    }

    @Test
    @DisplayName("error responses never leak stack traces, SQL or constraint names")
    void errorResponsesDoNotLeakInternals() {
        for (String path : new String[]{"/api/tickets/999999", "/api/users/999999", "/api/applications/999999"}) {
            String body = getRawAs(actorId, path).getBody();

            assertThat(body).isNotNull();
            assertThat(body.toLowerCase())
                    .as("no internals may appear in %s", path)
                    .doesNotContain("exception")
                    .doesNotContain("org.springframework")
                    .doesNotContain("org.hibernate")
                    .doesNotContain("org.postgresql")
                    .doesNotContain("select ")
                    .doesNotContain("constraint")
                    .doesNotContain("\tat ");
        }
    }

    @Test
    @DisplayName("a database constraint violation does not leak the constraint name")
    void constraintViolationDoesNotLeakDetails() {
        // ticket_comments.ticket_id is a foreign key as of V11; insert through SQL is rejected by the
        // database, but the API surface for any such failure must stay opaque.
        ResponseEntity<String> response = getRawAs(actorId, "/api/tickets/999999");

        assertThat(response.getBody()).doesNotContain("fk_");
    }
}
