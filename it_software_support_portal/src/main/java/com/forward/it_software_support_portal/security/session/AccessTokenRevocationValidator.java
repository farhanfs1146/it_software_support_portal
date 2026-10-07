package com.forward.it_software_support_portal.security.session;

import com.forward.it_software_support_portal.security.JwtClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Rejects an access token whose signature and expiry are perfectly valid but whose session has ended.
 *
 * <p>This is the piece that was missing from Phases 4 and 5, and it is worth being precise about what
 * it fixes. A signed JWT is a statement about the past: "at 10:00 this user held this role." Spring's
 * default validators check the signature, the expiry and the issuer, which together prove the statement
 * was genuinely made and is not stale by the clock. None of them can know that at 10:05 the account was
 * deactivated, the password was changed, or the user pressed "sign out everywhere". Without this
 * validator those events had no effect until the token expired - up to the full 30-minute TTL.
 *
 * <p>Four things are checked, in order, each failing closed:
 *
 * <ol>
 *   <li><strong>{@code sub} is a positive number.</strong> Anything else is not a token this
 *       application issued.</li>
 *   <li><strong>The {@code tv} claim is present and numeric.</strong> Tokens minted before Phase 7
 *       have no such claim; they are refused rather than waved through, which is the only safe reading
 *       of "this token predates revocation support". The cost is one re-login.</li>
 *   <li><strong>The account still exists and is active.</strong> This closes the deactivation window
 *       even if nobody remembered to bump the counter - a row edited directly in the database, for
 *       instance.</li>
 *   <li><strong>{@code tv} equals the account's current token version.</strong> One increment
 *       invalidates every token issued before it, with no denylist to grow.</li>
 * </ol>
 *
 * <p><strong>Why a validator and not a filter.</strong> Placing the check inside the {@code JwtDecoder}
 * means it runs before an {@code Authentication} is ever built, so no part of the application can see a
 * revoked principal - not a controller, not {@code @PreAuthorize}, not {@code CurrentUserProvider}.
 * A filter would have to be positioned correctly relative to the authentication filter to achieve the
 * same thing, and could be bypassed by any path that decodes a token directly.
 *
 * <p><strong>What the client is told.</strong> An {@code invalid_token} error, which Spring renders as
 * 401 with {@code WWW-Authenticate: Bearer}. The description is deliberately the same for a deactivated
 * account and a superseded token version: the holder of a revoked token learns that it no longer works,
 * and nothing about why.
 */
@Component
public class AccessTokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenRevocationValidator.class);

    private static final String DESCRIPTION = "The access token is no longer valid for this account.";

    private final PrincipalStateRegistry principalStateRegistry;

    public AccessTokenRevocationValidator(PrincipalStateRegistry principalStateRegistry) {
        this.principalStateRegistry = principalStateRegistry;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Optional<Long> subject = positiveLong(token.getSubject());
        if (subject.isEmpty()) {
            log.warn("Rejecting access token: subject '{}' is not a user id", token.getSubject());
            return failure();
        }
        Long userId = subject.get();

        Integer presentedVersion = claimAsInteger(token);
        if (presentedVersion == null) {
            log.warn("Rejecting access token for user {}: no usable '{}' claim. Tokens issued before "
                    + "session revocation support do not carry one.", userId, JwtClaims.TOKEN_VERSION);
            return failure();
        }

        Optional<PrincipalState> state = principalStateRegistry.lookup(userId);
        if (state.isEmpty()) {
            log.warn("Rejecting access token: user {} no longer exists", userId);
            return failure();
        }
        if (!state.get().active()) {
            log.info("Rejecting access token for user {}: the account is deactivated", userId);
            return failure();
        }
        if (state.get().tokenVersion() != presentedVersion) {
            log.info("Rejecting access token for user {}: it carries token version {} but the account "
                    + "is at {}, so the session was revoked", userId, presentedVersion,
                    state.get().tokenVersion());
            return failure();
        }

        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, DESCRIPTION, null));
    }

    /**
     * Reads {@code tv} tolerantly of JSON number typing. A claim round-tripped through JSON may arrive
     * as any {@link Number} subtype depending on the parser, so the claim is not cast to one.
     */
    private static Integer claimAsInteger(Jwt token) {
        Object raw = token.getClaim(JwtClaims.TOKEN_VERSION);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Optional<Long> positiveLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
