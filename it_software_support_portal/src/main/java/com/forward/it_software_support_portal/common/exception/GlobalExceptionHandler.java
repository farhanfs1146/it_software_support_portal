package com.forward.it_software_support_portal.common.exception;

import com.forward.it_software_support_portal.common.identity.MissingUserIdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
import com.forward.it_software_support_portal.security.ratelimit.TooManyLoginAttemptsException;
import com.forward.it_software_support_portal.security.session.InvalidRefreshTokenException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Translates exceptions into RFC 7807 {@link ProblemDetail} responses.
 *
 * <p>Before this existed there was no {@code @ControllerAdvice} at all: every "not found" threw a
 * bare {@code RuntimeException} and reached the client as HTTP 500, so callers could not tell their
 * own mistakes from server faults and monitoring could not tell routine 404 noise from real
 * incidents (audit finding P0-6).
 *
 * <p>Two rules govern everything here:
 * <ul>
 *   <li><strong>Never expose internals.</strong> Only messages this application authored are placed
 *       in {@code detail}. Database messages, constraint names and stack traces are logged, never
 *       returned.
 *   <li><strong>Never hide a real fault.</strong> Anything unrecognized stays a 500 and is logged at
 *       ERROR with its stack trace. This handler narrows status codes; it does not swallow problems.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        return problemDetail;
    }

    // ---------------------------------------------------------------- 404

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e) {
        log.debug("Resource not found: {}", e.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Resource not found", e.getMessage());
    }

    // ---------------------------------------------------------------- 400

    @ExceptionHandler(InvalidReferenceException.class)
    public ProblemDetail handleInvalidReference(InvalidReferenceException e) {
        log.debug("Invalid reference in request: {}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Invalid reference", e.getMessage());
    }

    /** Bean validation failures on {@code @Valid @RequestBody} arguments. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> violations = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            violations.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ProblemDetail problemDetail = problem(
                HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        problemDetail.setProperty("violations", violations);
        return problemDetail;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleHandlerValidation(HandlerMethodValidationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more parameters are invalid");
    }

    /** Unparseable body, or an enum value that does not exist. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body: {}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                "The request body could not be read. Check field types and enum values.");
    }

    /** A path variable or request parameter of the wrong type, including an invalid enum constant. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Parameter '" + e.getName() + "' has an invalid value");
    }

    /**
     * A required request parameter was not supplied - for example
     * {@code PATCH /api/tickets/{id}/status} without {@code ?status=}.
     *
     * <p>Mapped explicitly because of how handler resolution orders itself: Spring MVC has a correct
     * 400 for this in {@code DefaultHandlerExceptionResolver}, but {@code ExceptionHandlerExceptionResolver}
     * runs first, so the catch-all {@link #handleUnexpected} at the bottom of this class claimed it and
     * turned a caller's missing parameter into a 500 - the exact confusion between caller error and
     * server fault that this handler class exists to remove (audit finding P0-6).
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException e) {
        log.debug("Missing request parameter: {}", e.getParameterName());
        return problem(HttpStatus.BAD_REQUEST, "Missing parameter",
                "Required parameter '" + e.getParameterName() + "' is missing.");
    }

    // ---------------------------------------------------------------- 404 / 405 / 406 / 415

    /**
     * No endpoint is mapped to the requested path.
     *
     * <p>Same reason as {@link #handleMissingParameter}: without this, an unknown URL reached the
     * catch-all and was reported as a 500 and logged at ERROR with a stack trace, so routine
     * mistyped-URL noise looked identical to a real incident.
     *
     * <p>{@code NoResourceFoundException} is what an unmatched path produces once static-resource
     * handling declines it; {@code NoHandlerFoundException} is the variant thrown when no handler is
     * found at all. Both mean the same thing to a caller, so both answer 404.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ProblemDetail handleNoHandler(Exception e) {
        log.debug("No handler for request: {}", e.getMessage());
        return problem(HttpStatus.NOT_FOUND, "Resource not found",
                "No endpoint matches this request.");
    }

    /**
     * The path exists but not for this HTTP method.
     *
     * <p>Carries {@code Allow}, which RFC 9110 requires on a 405 response, so a client is told what the
     * path does support rather than having to guess.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.debug("Method not supported: {}", e.getMessage());

        ProblemDetail problemDetail = problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                "This endpoint does not support " + e.getMethod() + " requests.");

        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            response.allow(supported.toArray(HttpMethod[]::new));
        }
        return response.body(problemDetail);
    }

    /** The request body's content type is not one this API reads. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.debug("Unsupported media type: {}", e.getMessage());
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
                "This endpoint accepts application/json.");
    }

    /** Nothing this API can produce satisfies the caller's {@code Accept} header. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ProblemDetail handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.debug("Not acceptable: {}", e.getMessage());
        return problem(HttpStatus.NOT_ACCEPTABLE, "Not acceptable",
                "This endpoint produces application/json.");
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicate(DuplicateResourceException e) {
        log.debug("Duplicate resource: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, "Duplicate resource", e.getMessage());
    }

    // ---------------------------------------------------------------- 401 / 403

    /**
     * Authentication failed at the login endpoint.
     *
     * <p>401, and deliberately uninformative: the same response for an unknown email, a wrong password,
     * an account with no password set and a deactivated account. Distinguishing them would turn login
     * into an account-enumeration oracle.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        log.info("Failed authentication attempt");
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid email or password.");
    }

    /**
     * A refresh token could not be exchanged - <strong>401</strong>.
     *
     * <p>Uninformative for the same reason as login, and it matters more here. The holder of a refresh
     * token that failed is quite possibly an attacker holding a stolen one, and the interesting
     * question for them is <em>why</em> it failed: "expired" says the token was real,
     * "already used" says the victim is still active, "unknown" says it was never valid. One response
     * for all of them answers none of those.
     *
     * <p>A distinct title from login's, because the client's correct reaction differs: on a failed
     * refresh it must discard its stored tokens and send the user back to sign in, rather than retry.
     */
    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException e) {
        log.info("Failed refresh-token exchange");
        return problem(HttpStatus.UNAUTHORIZED, "Session expired",
                "The refresh token is invalid or has expired. Sign in again.");
    }

    /**
     * No usable caller identity reached the service layer.
     *
     * <p>401 now, where Phase 2 returned 400. That 400 was correct while no authentication scheme
     * existed - advertising 401 would have implied one. Real authentication exists, so the honest code
     * is 401.
     */
    @ExceptionHandler(MissingUserIdentityException.class)
    public ProblemDetail handleMissingIdentity(MissingUserIdentityException e) {
        log.debug("Missing caller identity: {}", e.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required",
                "A valid access token is required to call this endpoint.");
    }

    /**
     * Too many failed login attempts - <strong>429</strong>.
     *
     * <p>Returned instead of a 401 once throttling trips, and deliberately identical whatever the
     * submitted account was: a 429 that appeared only for real accounts would be an enumeration oracle,
     * which is exactly what the uniform 401 exists to avoid. The body carries no counter, no threshold
     * and no indication of which dimension (address or account) tripped.
     *
     * <p>{@code Retry-After} is in seconds, as RFC 9110 requires for a delta value. It is the only piece
     * of limiter state exposed, and it is the one a well-behaved client genuinely needs.
     */
    @ExceptionHandler(TooManyLoginAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException e) {
        log.warn("Login throttled; advising retry after {}s", e.getRetryAfterSeconds());

        ProblemDetail problemDetail = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
                "Too many failed login attempts. Try again later.");

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problemDetail);
    }

    /**
     * Authenticated, but lacking the permission - 403.
     *
     * <p>This handler is not optional. {@code @PreAuthorize} throws inside the dispatcher, so this
     * advice sees the exception before Spring Security's ExceptionTranslationFilter would. Without an
     * explicit mapping the catch-all below would turn every authorization denial into a 500, which
     * would both mislead clients and bury real faults.
     *
     * <p>The body names no permission or role; the detail is logged instead.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException e) {
        log.info("Authorization denied: {}", e.getMessage());
        return problem(HttpStatus.FORBIDDEN, "Access denied",
                "You do not have permission to perform this operation.");
    }

    // ---------------------------------------------------------------- 409

    /**
     * Two callers modified the same ticket concurrently and this one lost the race
     * (audit finding P1-4).
     *
     * <p>409 tells the client its read was stale and the write was refused - it must re-read and
     * retry. The alternative, which the code did before {@code @Version} existed, was to silently
     * overwrite the other writer's change.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException e) {
        log.info("Optimistic locking conflict: {}", e.getMessage());
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "This ticket was modified by someone else. Reload it and try again.");
    }

    /**
     * A database constraint rejected the write - a duplicate unique value, or a foreign key
     * pointing at a row that does not exist.
     *
     * <p>The exception's own message is deliberately discarded: it contains constraint names, column
     * names and SQL, which must not be exposed. It is logged at WARN instead.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return problem(HttpStatus.CONFLICT, "Conflicting data",
                "The request conflicts with existing data or references a record that does not exist.");
    }

    // ---------------------------------------------------------------- 500

    /**
     * Anything unrecognized. Stays a 500, is logged with its stack trace, and returns no internal
     * detail. This handler exists to stop leaking stack traces - not to make faults look like
     * successes.
     *
     * <p><strong>Note for anyone adding to this class.</strong> Because this method matches
     * {@code Exception}, and because {@code ExceptionHandlerExceptionResolver} runs before
     * {@code DefaultHandlerExceptionResolver}, it intercepts Spring MVC's own protocol exceptions too -
     * which Spring would otherwise map to the right 4xx itself. Every such exception therefore needs an
     * explicit handler above, or it silently becomes a 500. The ones that reach this API are already
     * mapped (missing parameter, no handler, wrong method, unsupported or unacceptable media type); a
     * new one introduced by a future endpoint will need the same treatment.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred.");
    }
}
