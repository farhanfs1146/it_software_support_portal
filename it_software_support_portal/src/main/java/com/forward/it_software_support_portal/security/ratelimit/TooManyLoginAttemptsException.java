package com.forward.it_software_support_portal.security.ratelimit;

/**
 * Raised when a login attempt is refused because too many recent attempts have failed.
 *
 * <p>Thrown <strong>before</strong> any password verification happens, which matters twice over: it
 * denies the attacker the BCrypt work that makes login expensive, and it means the response cannot
 * depend on whether the submitted account exists.
 *
 * @param retryAfterSeconds how long until the caller may try again; surfaced as the {@code Retry-After}
 *                          header. This is the only limiter state ever exposed - no counters, no
 *                          thresholds, no indication of which dimension (address or account) tripped.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("Too many failed login attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
