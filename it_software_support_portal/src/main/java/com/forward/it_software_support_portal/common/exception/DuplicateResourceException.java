package com.forward.it_software_support_portal.common.exception;

/**
 * Thrown when a request would create a record that violates a uniqueness rule - a duplicate employee
 * code or email, for example.
 *
 * <p>Mapped to HTTP 409 rather than 500. The service already checked for these cases and threw a
 * bare {@code RuntimeException}, so the caller was told "server error" for what is plainly a
 * conflict with existing data (audit finding P0-6).
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
