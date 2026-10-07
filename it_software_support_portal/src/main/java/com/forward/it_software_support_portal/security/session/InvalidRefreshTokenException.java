package com.forward.it_software_support_portal.security.session;

/**
 * A refresh token could not be exchanged: unknown, already rotated, revoked, expired, or belonging to
 * an account that can no longer authenticate.
 *
 * <p>Every one of those causes produces this one exception with the same message, and
 * {@code GlobalExceptionHandler} renders it as an identical 401. Distinguishing them in the response
 * would tell an attacker holding a stolen token whether it was ever valid, whether it had been used,
 * and whether the account still exists - the same enumeration oracle Phase 4 removed from login.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
