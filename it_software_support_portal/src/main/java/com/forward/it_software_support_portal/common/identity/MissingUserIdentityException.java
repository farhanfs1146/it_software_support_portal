package com.forward.it_software_support_portal.common.identity;

/**
 * Thrown when an operation needs to know who is performing it and no caller identity was supplied.
 *
 * <p>This exists so the system can <strong>fail loudly instead of silently attributing the action to
 * user id 1</strong>, which is what the code did before (audit finding P0-3).
 */
public class MissingUserIdentityException extends RuntimeException {

    public MissingUserIdentityException(String message) {
        super(message);
    }
}
