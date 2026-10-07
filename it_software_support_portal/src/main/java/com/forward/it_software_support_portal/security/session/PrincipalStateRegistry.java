package com.forward.it_software_support_portal.security.session;

import java.util.Optional;

/**
 * Supplies the account state used to validate every access token.
 *
 * <p>An interface, for the same reason {@code LoginAttemptLimiter} is one: the current implementation
 * keeps a short-lived in-process cache, and a deployment that runs more than one instance can replace
 * it with a shared-store implementation without touching the validator or anything else.
 */
public interface PrincipalStateRegistry {

    /** @return the account's current state, or empty if no such user exists. */
    Optional<PrincipalState> lookup(Long userId);

    /**
     * Drops any cached state for a user, so the next token validation re-reads it.
     *
     * <p>Called by whatever just changed the account - a password change, a deactivation, a role
     * change, a sign-out-everywhere. This is what makes revocation take effect <em>immediately</em>
     * on this instance rather than after the cache TTL.
     */
    void invalidate(Long userId);
}
