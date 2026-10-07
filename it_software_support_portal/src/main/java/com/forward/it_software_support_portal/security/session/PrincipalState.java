package com.forward.it_software_support_portal.security.session;

/**
 * What the resource server needs to know about an account to decide whether a presented access token
 * is still good: the account's current revocation counter, and whether it is still active.
 *
 * @param tokenVersion the value a token's {@code tv} claim must equal
 * @param active       false once the account is deactivated; such a token is refused immediately
 */
public record PrincipalState(int tokenVersion, boolean active) {
}
