package com.forward.it_software_support_portal.security.session;

import com.forward.it_software_support_portal.entity.User;

/**
 * Issues, rotates and revokes refresh tokens - the whole session lifecycle on the server side.
 *
 * <p>An interface for the same reason the other security abstractions in this package are: the
 * implementation keeps state in the database, and nothing above it should depend on that.
 */
public interface RefreshTokenService {

    /**
     * Starts a new session: a new rotation family with its first token.
     *
     * @return the plaintext token, which is the only copy - the row holds only its hash
     */
    IssuedRefreshToken issueFor(User user);

    /**
     * Exchanges a refresh token for its successor, revoking the one presented.
     *
     * <p>This is where replay is caught. Presenting a token that has already been rotated means either
     * a stolen token being replayed or the real client racing a thief; the two are indistinguishable
     * from here, so the whole family is revoked and both parties must authenticate again.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, already rotated, revoked, expired,
     *                                      or belongs to an account that can no longer authenticate.
     *                                      One exception for every cause, so the response reveals
     *                                      nothing about which.
     */
    RotatedSession rotate(String rawToken);

    /**
     * Revokes the single token presented, ending that one session.
     *
     * <p>Deliberately silent about whether the token existed: logout is idempotent, and an unknown
     * token must not be distinguishable from a valid one.
     */
    void logout(String rawToken);

    /**
     * Ends every session for a user: revokes all their refresh tokens <em>and</em> increments their
     * token version so already-issued access tokens stop working too.
     *
     * <p>Both halves are necessary. Revoking refresh tokens alone would leave access tokens valid for
     * the rest of their TTL; bumping the version alone would leave the refresh tokens able to mint
     * fresh ones.
     *
     * @return how many refresh tokens were revoked
     */
    int revokeAllSessions(User user, RevocationReason reason);

    /** How many live refresh tokens a user currently holds. */
    long liveSessionCount(Long userId);

    /** @param rawToken the plaintext handed to the client; never stored */
    record IssuedRefreshToken(String rawToken, long expiresInSeconds) {
    }

    /**
     * The outcome of a successful rotation.
     *
     * @param user         reloaded, so the new access token carries the account's current role and
     *                     token version rather than whatever was true when the session started
     * @param refreshToken the successor token, replacing the one presented
     */
    record RotatedSession(User user, IssuedRefreshToken refreshToken) {
    }
}
