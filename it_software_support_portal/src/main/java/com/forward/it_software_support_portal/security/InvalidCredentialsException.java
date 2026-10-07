package com.forward.it_software_support_portal.security;

/**
 * Authentication failed.
 *
 * <p>Thrown for every failure mode - unknown email, wrong password, no password set, deactivated
 * account - with the same message. That uniformity is the point: distinguishing "no such user" from
 * "wrong password" turns the login endpoint into an account-enumeration oracle. The real reason is
 * logged server-side.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
