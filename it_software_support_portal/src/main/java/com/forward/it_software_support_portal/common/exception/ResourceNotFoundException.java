package com.forward.it_software_support_portal.common.exception;

/**
 * Thrown when a referenced entity does not exist.
 *
 * <p>Replaces the bare {@code RuntimeException("... not found")} calls that previously surfaced to
 * callers as HTTP 500 (audit finding P0-6). Mapped to 404 by
 * {@link GlobalExceptionHandler} when the caller asked for the resource directly, and the message
 * keeps the same wording the services used before so log output stays familiar.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String resource, Object id) {
        return new ResourceNotFoundException(resource + " not found with id " + id);
    }
}
