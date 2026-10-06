package com.forward.it_software_support_portal.security.ratelimit;

/**
 * Tracks failed login attempts and refuses further ones once a threshold is crossed.
 *
 * <p><strong>Why this is an interface.</strong> The current implementation keeps its counters in the
 * application's own memory, which protects a single instance only. When this application runs as more
 * than one instance, each would enforce the limits independently and an attacker spreading attempts
 * across instances would get a correspondingly higher effective budget. Replacing this with a shared
 * store is then a matter of providing a different bean - nothing in {@code AuthServiceImpl} changes.
 * The limitation is stated plainly in docs/SECURITY.md rather than glossed over.
 *
 * <p>Implementations must be thread-safe: login is unauthenticated and therefore trivially easy to
 * call concurrently.
 */
public interface LoginAttemptLimiter {

    /**
     * Checks whether an attempt may proceed.
     *
     * <p>Called before credentials are verified, so neither argument may be assumed to identify a real
     * account - {@code email} is whatever the caller submitted.
     *
     * @throws TooManyLoginAttemptsException if the attempt must be refused
     */
    void checkAllowed(String email, String clientIp);

    /** Records a failed attempt against both the submitted account and the calling address. */
    void recordFailure(String email, String clientIp);

    /** Clears the failure state for a successful authentication. */
    void recordSuccess(String email, String clientIp);
}
