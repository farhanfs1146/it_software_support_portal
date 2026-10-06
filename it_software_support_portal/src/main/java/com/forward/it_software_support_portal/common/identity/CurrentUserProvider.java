package com.forward.it_software_support_portal.common.identity;

/**
 * Resolves the id of the user performing the current operation.
 *
 * <p><strong>Why this interface exists.</strong> It is the seam between business logic and whatever
 * mechanism happens to establish identity. Services ask this one question and depend on nothing from
 * Spring Security, so they stay unit-testable and the authentication mechanism can be replaced without
 * touching them. That has now happened once, which is the point:
 *
 * <ol>
 *   <li>Originally {@code TicketServiceImpl} hardcoded {@code userRepository.findById(1L)}, so every
 *       ticket was attributed to whoever held user id 1 (audit finding P0-3).
 *   <li>Phase 2 introduced this interface with an implementation that read an {@code X-User-Id} header -
 *       explicitly not authentication, but fail-closed and honest about who was acting.
 *   <li>Phase 4 replaced that implementation with one that reads the authenticated principal from a
 *       verified access token. <strong>No service changed.</strong>
 * </ol>
 *
 * <p>The same seam is where an external identity provider would arrive, and where organisation/tenant
 * context would eventually be read from the principal - see docs/SECURITY.md.
 *
 * <p>Implementations must never trust a client-supplied value as identity, and must never fall back to
 * a default user.
 */
public interface CurrentUserProvider {

    /**
     * @return the id of the user performing the current operation
     * @throws MissingUserIdentityException if no authenticated caller is present. Callers must never
     *                                      substitute a default user - that is the defect this
     *                                      interface exists to prevent.
     */
    Long requireCurrentUserId();
}
