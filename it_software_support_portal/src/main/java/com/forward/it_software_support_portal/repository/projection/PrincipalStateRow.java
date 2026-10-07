package com.forward.it_software_support_portal.repository.projection;

/**
 * The two fields the resource server needs about an account on every authenticated request:
 * its revocation counter and whether it is still active.
 *
 * <p>A projection rather than the {@code User} entity for the same reason {@link UserRow} is one -
 * security, not N+1. Loading the entity would pull {@code password_hash} into the persistence context
 * on every single request. This selects two columns and nothing else.
 */
public record PrincipalStateRow(Integer tokenVersion, Boolean active) {
}
