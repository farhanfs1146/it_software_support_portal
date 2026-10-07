package com.forward.it_software_support_portal.common.exception;

/**
 * Thrown when a request body references an entity that does not exist - for example creating a
 * ticket for an unknown {@code applicationId}, or assigning one to an unknown user.
 *
 * <p>Distinguished from {@link ResourceNotFoundException} on purpose: the resource the caller
 * addressed does exist (or is being created), but a value *inside* their payload is wrong. That is a
 * 400, not a 404 - the URL was fine, the body was not. Previously both produced HTTP 500
 * (audit finding P0-6).
 */
public class InvalidReferenceException extends RuntimeException {

    public InvalidReferenceException(String message) {
        super(message);
    }

    public static InvalidReferenceException of(String field, Object value) {
        return new InvalidReferenceException(field + " does not reference an existing record: " + value);
    }
}
