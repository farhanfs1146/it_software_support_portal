package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.common.exception.DuplicateResourceException;
import com.forward.it_software_support_portal.common.exception.GlobalExceptionHandler;
import com.forward.it_software_support_portal.common.exception.InvalidReferenceException;
import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.common.identity.MissingUserIdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic proof of the exception-to-status mapping (audit finding P0-6).
 *
 * <p>Deliberately a plain unit test: it needs no database and no Docker, so the mapping stays
 * verifiable on any machine. It is also the only way to assert the optimistic-locking mapping without
 * depending on a particular thread interleaving - {@code ConcurrencyRegressionTest} forces a real
 * conflict, this pins down what the conflict turns into.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a missing resource maps to 404")
    void notFoundMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(
                ResourceNotFoundException.of("Ticket", 42L));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
        assertThat(problem.getDetail()).contains("Ticket").contains("42");
    }

    @Test
    @DisplayName("an invalid reference in the body maps to 400")
    void invalidReferenceMapsTo400() {
        ProblemDetail problem = handler.handleInvalidReference(
                InvalidReferenceException.of("applicationId", 999L));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid reference");
    }

    @Test
    @DisplayName("a missing caller identity maps to 401 now that authentication exists")
    void missingIdentityMapsTo401() {
        ProblemDetail problem = handler.handleMissingIdentity(
                new MissingUserIdentityException("No authenticated user is present"));

        assertThat(problem.getStatus())
                .as("""
                        Phase 2 returned 400 here because no authentication scheme existed and \n                        advertising 401 would have implied one. Phase 4 made authentication real, so \n                        401 is now the honest code.""")
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problem.getTitle()).isEqualTo("Authentication required");
    }

    @Test
    @DisplayName("a duplicate maps to 409")
    void duplicateMapsTo409() {
        ProblemDetail problem = handler.handleDuplicate(
                new DuplicateResourceException("Email already exists"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Duplicate resource");
    }

    @Test
    @DisplayName("an optimistic locking conflict maps to 409, never 500")
    void optimisticLockMapsTo409() {
        ProblemDetail problem = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("Row was updated by another transaction"));

        assertThat(problem.getStatus())
                .as("""
                        A lost-update conflict is the client's problem to retry, not a server fault. \
                        Audit finding P1-4 required this not be hidden behind a generic 500.""")
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Concurrent modification");
        assertThat(problem.getDetail()).contains("Reload");
    }

    @Test
    @DisplayName("a constraint violation maps to 409 and leaks no database detail")
    void dataIntegrityMapsTo409WithoutLeaking() {
        ProblemDetail problem = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "ERROR: insert or update on table \"tickets\" violates foreign key constraint "
                        + "\"fk_tickets_raised_by\" Detail: Key (raised_by)=(99) is not present"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail())
                .as("constraint names, column names and SQL must never reach the client")
                .doesNotContain("fk_tickets_raised_by")
                .doesNotContain("raised_by")
                .doesNotContain("tickets");
    }

    // ------------------------------------------------------------------
    // Spring MVC's own protocol exceptions.
    //
    // These all have a correct 4xx in Spring's DefaultHandlerExceptionResolver, but
    // ExceptionHandlerExceptionResolver runs first, so the catch-all handleUnexpected below used to
    // claim them and report every one as a 500 - logged at ERROR with a stack trace, which is the
    // caller-error/server-fault confusion audit finding P0-6 set out to remove. Each needs an explicit
    // mapping, and each is pinned here.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing required request parameter maps to 400, not 500")
    void missingParameterMapsTo400() {
        ProblemDetail problem = handler.handleMissingParameter(
                new MissingServletRequestParameterException("status", "TicketStatus"));

        assertThat(problem.getStatus())
                .as("PATCH /api/tickets/{id}/status without ?status= is a caller mistake, not a fault")
                .isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Missing parameter");
        assertThat(problem.getDetail()).contains("status");
    }

    @Test
    @DisplayName("an unmapped URL maps to 404, not 500")
    void unmappedUrlMapsTo404() {
        ProblemDetail problem = handler.handleNoHandler(
                new NoResourceFoundException(HttpMethod.GET, "/api/tickts", "api/tickts"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
    }

    @Test
    @DisplayName("the wrong HTTP method maps to 405 and advertises Allow")
    void wrongMethodMapsTo405WithAllowHeader() {
        ResponseEntity<ProblemDetail> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", List.of("PATCH")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
                .as("RFC 9110 requires Allow on a 405 so the client is told what the path does support")
                .containsExactly(HttpMethod.PATCH);
    }

    @Test
    @DisplayName("an unreadable content type maps to 415, not 500")
    void unsupportedMediaTypeMapsTo415() {
        ProblemDetail problem = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException(
                        MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
    }

    @Test
    @DisplayName("an unsatisfiable Accept header maps to 406, not 500")
    void notAcceptableMapsTo406() {
        ProblemDetail problem = handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
    }

    @Test
    @DisplayName("an unrecognised exception stays a 500 and exposes nothing")
    void unexpectedStaysA500() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("connection pool exhausted at com.zaxxer.hikari..."));

        assertThat(problem.getStatus())
                .as("this handler narrows status codes; it must not disguise real faults")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail())
                .isEqualTo("An unexpected error occurred.")
                .doesNotContain("hikari");
    }
}
