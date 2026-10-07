package com.forward.it_software_support_portal.security.session;

import com.forward.it_software_support_portal.entity.RefreshToken;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.repository.RefreshTokenRepository;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The refresh-token lifecycle, backed by the {@code refresh_tokens} table.
 *
 * <h2>Rotation, and why every refresh issues a new token</h2>
 *
 * A refresh token lives for days, so it is a far more valuable thing to steal than a 30-minute access
 * token. Rotation limits what a theft is worth: each token can be exchanged exactly once, so a stolen
 * copy is useful only until the legitimate client next refreshes - and the moment either party uses a
 * token the other already spent, the server sees a token being used twice and kills the whole family.
 *
 * <p>Both parties are signed out by that, including the innocent one. That is the intended behaviour
 * rather than a rough edge: the server cannot tell the victim from the thief, and the safe reading of
 * "this credential is in two places" is that it is compromised. The cost is one re-authentication.
 *
 * <h2>Concurrency</h2>
 *
 * Two refreshes with the same token racing each other is a real case - a client with parallel requests
 * all noticing a 401 at once. It is resolved by the database, not by locking: the revoking update is
 * conditional on the row still being live ({@code revoked_at is null}), so exactly one of the racers
 * updates a row and the loser sees zero rows changed and is rejected. Reading the row, deciding in
 * Java, and then writing would let both racers through.
 *
 * <p>The loser is rejected as a reuse, which revokes the family. A client that must tolerate parallel
 * refreshes should serialise them, which is the normal expectation for a rotating-token client.
 *
 * <h2>Every method is transactional</h2>
 *
 * Revocation and issue must be atomic. A crash between "revoke the presented token" and "store its
 * successor" would otherwise leave the user with no usable session and no way back except re-entering
 * their password.
 */
@Service
public class PersistentRefreshTokenService implements RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(PersistentRefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenGenerator generator;
    private final RefreshTokenHasher hasher;
    private final PrincipalStateRegistry principalStateRegistry;
    private final RefreshTokenFamilyRevoker familyRevoker;
    private final Clock clock;
    private final Duration refreshTtl;
    private final int maxSessionsPerUser;

    public PersistentRefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                                         UserRepository userRepository,
                                         RefreshTokenGenerator generator,
                                         RefreshTokenHasher hasher,
                                         PrincipalStateRegistry principalStateRegistry,
                                         RefreshTokenFamilyRevoker familyRevoker,
                                         SecurityProperties properties,
                                         Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.generator = generator;
        this.hasher = hasher;
        this.principalStateRegistry = principalStateRegistry;
        this.familyRevoker = familyRevoker;
        this.clock = clock;
        this.refreshTtl = properties.session().refreshTtl();
        this.maxSessionsPerUser = properties.session().maxSessionsPerUser();
    }

    @Override
    @Transactional
    public IssuedRefreshToken issueFor(User user) {
        IssuedRefreshToken issued = store(user.getId(), UUID.randomUUID().toString());
        enforceSessionCap(user.getId());
        return issued;
    }

    @Override
    @Transactional
    public RotatedSession rotate(String rawToken) {
        LocalDateTime now = now();
        RefreshToken presented = findPresented(rawToken);

        if (presented.isExpiredAt(now)) {
            // Not a reuse. An expired token is the ordinary end of a session that sat unused, so the
            // family is left alone - revoking it would punish a user for coming back after a holiday.
            log.info("Refresh rejected for user {}: the token expired at {}",
                    presented.getUserId(), presented.getExpiresAt());
            throw invalidToken();
        }

        // The account is checked BEFORE the presented token is spent, and the order matters: every
        // path that rejects from here on revokes the family through RefreshTokenFamilyRevoker, which
        // opens its own transaction. Had this transaction already updated a row in the family, that
        // new transaction would block on the lock this one holds. See RefreshTokenFamilyRevoker.
        User user = userRepository.findById(presented.getUserId())
                .orElseThrow(() -> {
                    log.warn("Refresh rejected: token names user {}, who no longer exists",
                            presented.getUserId());
                    return invalidToken();
                });

        if (!Boolean.TRUE.equals(user.getActive())) {
            // Reached only when the account was deactivated outside the API - deactivating through
            // PATCH /api/users/{id}/status already revokes everything. The session is ended here so
            // that a direct database edit is not left with live tokens behind it.
            familyRevoker.revokeFamilyDurably(
                    presented.getFamilyId(), RevocationReason.ACCOUNT_DEACTIVATED, now);
            log.info("Refresh rejected for user {}: the account is deactivated; its session was ended",
                    user.getId());
            throw invalidToken();
        }
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            // V13 defines a null hash as "cannot authenticate". The family is left as it is: this
            // state is only reachable by direct database edit, and the refusal is re-evaluated on
            // every attempt, so the token is already unusable.
            log.info("Refresh rejected for user {}: the account has no password set", user.getId());
            throw invalidToken();
        }

        // Spend the presented token, and only that token. The conditional update is what decides a
        // concurrent refresh: exactly one caller can take a live row to revoked, so a zero row count
        // means this token had already been spent - which is the replay signal.
        //
        // Scoped to this one row rather than the family on purpose. Revoking the family here would
        // revoke the legitimate successor that a racing refresh had just created, and the racer would
        // then look like a fresh rotation rather than a reuse - defeating the detection entirely.
        if (refreshTokenRepository.revokeIfLive(
                presented.getId(), RevocationReason.ROTATED.name(), now) == 0) {
            handleReuse(presented, now);
            throw invalidToken();
        }

        // The successor stays in the same family, so a later replay of any ancestor still revokes the
        // whole chain rather than only the one token presented.
        IssuedRefreshToken successor = store(user.getId(), presented.getFamilyId());
        log.info("Rotated the refresh token for user {}", user.getId());
        return new RotatedSession(user, successor);
    }

    /**
     * Revoking the whole family, not just the replayed token, is the entire value of the family id.
     * Revoking one token would leave the thief's successor - the token they obtained by rotating the
     * stolen one - perfectly valid, so the detection would achieve nothing.
     *
     * <p>It goes through {@link RefreshTokenFamilyRevoker} because the caller throws immediately
     * afterwards, and a rolled-back revocation is no revocation at all. That class records how the
     * first version of this code got it wrong.
     */
    private void handleReuse(RefreshToken presented, LocalDateTime now) {
        int killed = familyRevoker.revokeFamilyDurably(
                presented.getFamilyId(), RevocationReason.REUSE_DETECTED, now);
        log.warn("""
                        Refresh-token reuse detected for user {}: a token in family {} was presented after \
                        it had already been exchanged. Revoked {} further token(s) in that family; every \
                        session in it must authenticate again. Either the token was stolen, or a client \
                        is refreshing concurrently with the same token.""",
                presented.getUserId(), presented.getFamilyId(), killed);
    }

    @Override
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Optional<RefreshToken> token = refreshTokenRepository.findByTokenHash(hasher.hash(rawToken));
        if (token.isEmpty()) {
            // Idempotent and silent on purpose. Reporting "no such token" would turn logout into an
            // oracle for whether a guessed token is real, and a client retrying a logout after a
            // network failure should not get an error for succeeding twice.
            log.debug("Logout presented an unknown refresh token; nothing to do");
            return;
        }
        RefreshToken presented = token.get();
        if (presented.isRevoked()) {
            return;
        }
        // The whole family, not just this token: the point of logging out is that the session ends,
        // and the successors of this token are the same session.
        int revoked = refreshTokenRepository.revokeFamily(
                presented.getFamilyId(), RevocationReason.LOGOUT.name(), now());
        log.info("Logged out user {}: revoked {} refresh token(s)", presented.getUserId(), revoked);
    }

    @Override
    @Transactional
    public int revokeAllSessions(User user, RevocationReason reason) {
        int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), reason.name(), now());

        // The counter is what ends already-issued ACCESS tokens; revoking refresh tokens alone would
        // leave them usable for the rest of their TTL. A read-modify-write is safe here because the
        // only requirement is that the value CHANGES - two concurrent revocations both writing n+1
        // still invalidate every token carrying n.
        int current = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        user.setTokenVersion(current + 1);
        userRepository.save(user);

        // Write-through, so the revocation takes effect on the very next request on this instance
        // rather than after the state-cache TTL.
        invalidateNowAndAfterCommit(user.getId());

        log.info("Revoked every session for user {} ({}): {} refresh token(s), access tokens "
                + "invalidated by moving token version to {}", user.getId(), reason, revoked, current + 1);
        return revoked;
    }

    @Override
    @Transactional(readOnly = true)
    public long liveSessionCount(Long userId) {
        return refreshTokenRepository.countLiveForUser(userId);
    }

    /**
     * Clears the cached account state twice: now, and again once the transaction has completed.
     *
     * <p><strong>Clearing it only once, before the commit, leaves a window.</strong> The new token
     * version is not visible to any other transaction until this one commits, so a concurrent request
     * arriving between the invalidation and the commit would re-read the <em>old</em> version and cache
     * it again - and the token this call was revoking would keep working for up to the cache TTL. The
     * window is narrow, which is exactly what makes it the kind of bug that survives review and then
     * fails rarely in production.
     *
     * <p>The first invalidation still matters: it covers reads later in this same request, which do see
     * the uncommitted value.
     *
     * <p>{@code afterCompletion} rather than {@code afterCommit}, so a <em>rollback</em> also clears
     * anything cached mid-transaction. Dropping a cache entry is always safe - the cost is one reload -
     * so the broader hook is the right one.
     */
    private void invalidateNowAndAfterCommit(Long userId) {
        principalStateRegistry.invalidate(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    principalStateRegistry.invalidate(userId);
                }
            });
        }
    }

    private RefreshToken findPresented(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalidToken();
        }
        return refreshTokenRepository.findByTokenHash(hasher.hash(rawToken))
                .orElseThrow(() -> {
                    log.info("Refresh rejected: the presented token matches no stored session");
                    return invalidToken();
                });
    }

    private IssuedRefreshToken store(Long userId, String familyId) {
        String rawToken = generator.generate();
        LocalDateTime now = now();

        RefreshToken entity = new RefreshToken();
        entity.setTokenHash(hasher.hash(rawToken));
        entity.setUserId(userId);
        entity.setFamilyId(familyId);
        entity.setIssuedAt(now);
        entity.setExpiresAt(now.plus(refreshTtl));
        refreshTokenRepository.save(entity);

        return new IssuedRefreshToken(rawToken, refreshTtl.toSeconds());
    }

    /**
     * Keeps the number of simultaneous sessions per user bounded, revoking the oldest past the cap.
     *
     * <p>Two reasons, one security and one operational. A leaked password used from many devices would
     * otherwise accumulate unbounded live sessions, each surviving independently of the others; and the
     * table would grow with every sign-in that is never signed out. The oldest are dropped rather than
     * the newest refused, so a legitimate new sign-in is never blocked by stale sessions on devices the
     * user no longer has.
     */
    private void enforceSessionCap(Long userId) {
        if (maxSessionsPerUser <= 0) {
            return;
        }
        long live = refreshTokenRepository.countLiveForUser(userId);
        if (live <= maxSessionsPerUser) {
            return;
        }

        LocalDateTime now = now();
        List<RefreshToken> excess = refreshTokenRepository
                .findByUserIdAndRevokedAtIsNullOrderByIssuedAtAscIdAsc(userId).stream()
                .limit(live - maxSessionsPerUser)
                .toList();

        for (RefreshToken token : excess) {
            refreshTokenRepository.revokeFamily(
                    token.getFamilyId(), RevocationReason.SESSION_CAP.name(), now);
        }
        log.info("User {} exceeded the {}-session cap; revoked the {} oldest session(s)",
                userId, maxSessionsPerUser, excess.size());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), SessionTimeConfig.STORAGE_ZONE);
    }

    private static InvalidRefreshTokenException invalidToken() {
        return new InvalidRefreshTokenException("The refresh token is invalid or has expired");
    }
}
