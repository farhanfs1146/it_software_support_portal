package com.forward.it_software_support_portal.security;

/**
 * The names of the claims this application puts in its access tokens.
 *
 * <p>Collected in one place because they are now read by three unrelated collaborators - the issuer
 * ({@code JwtTokenService}), the authority mapper ({@code JwtRoleAuthoritiesConverter}) and the
 * revocation validator ({@code session/AccessTokenRevocationValidator}) - and a claim name that drifts
 * between issuer and verifier does not fail loudly. It fails as "no role claim, so no authorities", or
 * as "no version claim, so every token is rejected".
 *
 * <p>Standard registered claims ({@code iss}, {@code sub}, {@code iat}, {@code exp}, {@code jti}) are
 * not listed here: Spring Security's {@code JwtClaimsSet} builder has typed methods for those, so
 * there is no string to get wrong.
 */
public final class JwtClaims {

    /** The authenticated user's email address. Informational; authorization never reads it. */
    public static final String EMAIL = "email";

    /**
     * The user's role at the time of issue. {@code JwtRoleAuthoritiesConverter} expands it into the
     * permission authorities that {@code @PreAuthorize} checks.
     */
    public static final String ROLE = "role";

    /**
     * The account's revocation counter at the time of issue. A token whose value no longer matches
     * {@code users.token_version} has been revoked. See
     * {@code session/AccessTokenRevocationValidator}.
     */
    public static final String TOKEN_VERSION = "tv";

    private JwtClaims() {
    }
}
