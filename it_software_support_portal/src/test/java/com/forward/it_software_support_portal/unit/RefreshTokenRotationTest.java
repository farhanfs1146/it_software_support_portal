package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.entity.RefreshToken;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.repository.RefreshTokenRepository;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.SecurityProperties;
import com.forward.it_software_support_portal.security.session.InvalidRefreshTokenException;
import com.forward.it_software_support_portal.security.session.PersistentRefreshTokenService;
import com.forward.it_software_support_portal.security.session.PrincipalStateRegistry;
import com.forward.it_software_support_portal.security.session.RefreshTokenGenerator;
import com.forward.it_software_support_portal.security.session.RefreshTokenFamilyRevoker;
import com.forward.it_software_support_portal.security.session.RefreshTokenHasher;
import com.forward.it_software_support_portal.security.session.RefreshTokenService;
import com.forward.it_software_support_portal.security.session.RevocationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The refresh-token rules, as rules - with a mocked repository and a controllable clock.
 *
 * <p><strong>Why these live in a unit test and not only in the integration suite.</strong> Two
 * reasons. The expiry rules are statements about time, and a test that proved them against the wall
 * clock would have to sleep for seven days; here the clock is a field and time moves instantly. And
 * this machine has no container runtime, so every {@code @DatabaseIntegrationTest} is skipped on it -
 * a rule verified only there is a rule nobody can check locally. The database-dependent half of the
 * behaviour (the conditional update that decides a concurrent refresh, the real foreign keys) is
 * covered by {@code security/SessionLifecycleSecurityTest}, which genuinely needs a database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Refresh-token issue, rotation and revocation rules")
class RefreshTokenRotationTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PrincipalStateRegistry principalStateRegistry;

    @Mock
    private RefreshTokenFamilyRevoker familyRevoker;

    private MutableClock clock;
    private PersistentRefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new PersistentRefreshTokenService(
                refreshTokenRepository,
                userRepository,
                new RefreshTokenGenerator(),
                new RefreshTokenHasher(),
                principalStateRegistry,
                familyRevoker,
                properties(REFRESH_TTL, 10),
                clock);

        user = user(7L, Role.IT_SUPPORT, true, 3);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenRepository.countLiveForUser(anyLong())).thenReturn(1L);
    }

    // ---------------------------------------------------------------- issue

    @Test
    @DisplayName("issuing stores only a hash, never the token the client receives")
    void issueStoresOnlyTheHash() {
        RefreshTokenService.IssuedRefreshToken issued = service.issueFor(user);

        RefreshToken stored = capturedToken();
        assertThat(issued.rawToken()).isNotBlank();
        assertThat(stored.getTokenHash())
                .describedAs("the plaintext token must not be recoverable from the row")
                .isNotEqualTo(issued.rawToken())
                .hasSize(64)
                .matches("[0-9a-f]{64}");
        assertThat(new RefreshTokenHasher().hash(issued.rawToken()))
                .describedAs("the stored hash must be the hash OF the issued token")
                .isEqualTo(stored.getTokenHash());
    }

    @Test
    @DisplayName("issuing sets expiry from the configured TTL and the injected clock")
    void issueSetsExpiryFromTtl() {
        RefreshTokenService.IssuedRefreshToken issued = service.issueFor(user);

        RefreshToken stored = capturedToken();
        assertThat(stored.getIssuedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(stored.getExpiresAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW.plus(REFRESH_TTL), ZoneOffset.UTC));
        assertThat(issued.expiresInSeconds()).isEqualTo(REFRESH_TTL.toSeconds());
    }

    @Test
    @DisplayName("each login starts a new family, so sessions are independent of each other")
    void eachLoginStartsItsOwnFamily() {
        service.issueFor(user);
        service.issueFor(user);

        List<RefreshToken> stored = capturedTokens();
        assertThat(stored.get(0).getFamilyId())
                .describedAs("two separate sign-ins must not share a rotation chain - revoking one "
                        + "family would otherwise sign the user out of the other device too")
                .isNotEqualTo(stored.get(1).getFamilyId());
    }

    @Test
    @DisplayName("two issued tokens are never the same value")
    void issuedTokensAreUnique() {
        String first = service.issueFor(user).rawToken();
        String second = service.issueFor(user).rawToken();

        assertThat(first).isNotEqualTo(second);
    }

    // ---------------------------------------------------------------- rotate

    @Test
    @DisplayName("rotating revokes the presented token and issues a successor in the same family")
    void rotationKeepsTheFamilyAndReplacesTheToken() {
        String raw = "the-presented-token";
        RefreshToken live = storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null);
        givenStored(raw, live);
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(1);

        RefreshTokenService.RotatedSession rotated = service.rotate(raw);

        verify(refreshTokenRepository).revokeIfLive(
                eq(1L), eq(RevocationReason.ROTATED.name()), any(LocalDateTime.class));
        assertThat(rotated.refreshToken().rawToken()).isNotEqualTo(raw);
        assertThat(capturedToken().getFamilyId())
                .describedAs("the successor stays in the family, so replaying any ancestor still "
                        + "revokes the whole chain")
                .isEqualTo("family-A");
    }

    @Test
    @DisplayName("rotation returns the reloaded user, so a changed role reaches the new access token")
    void rotationReloadsTheUser() {
        String raw = "token";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(1);
        user.setRole(Role.ADMIN);

        RefreshTokenService.RotatedSession rotated = service.rotate(raw);

        assertThat(rotated.user().getRole())
                .describedAs("the token must be minted from current state, not from whatever was "
                        + "true when the session started")
                .isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("an unknown token is refused without touching anything")
    void unknownTokenIsRefused() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("never-issued"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).revokeIfLive(anyLong(), anyString(), any());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("a blank or null token is refused without a database lookup")
    void blankTokenIsRefused() {
        assertThatThrownBy(() -> service.rotate(null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> service.rotate("  "))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).findByTokenHash(anyString());
    }

    // ---------------------------------------------------------- reuse detection

    @Test
    @DisplayName("replaying a spent token revokes the WHOLE family, not just the replayed token")
    void reuseRevokesTheEntireFamily() {
        String raw = "already-spent";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL),
                NOW.plusSeconds(60)));
        // The conditional update reports zero rows: somebody already spent this token.
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(familyRevoker).revokeFamilyDurably(
                eq("family-A"), eq(RevocationReason.REUSE_DETECTED), any(LocalDateTime.class));
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("the loser of a concurrent refresh is treated as a reuse, not waved through")
    void concurrentRefreshLoserIsTreatedAsReuse() {
        String raw = "contended";
        // The row looked live when it was read - this is exactly the race: both callers read a live
        // row, and the database lets only one of them revoke it.
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.rotate(raw))
                .describedAs("deciding from the row that was read, rather than from the update's row "
                        + "count, would let both racers rotate the same token")
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(familyRevoker).revokeFamilyDurably(
                eq("family-A"), eq(RevocationReason.REUSE_DETECTED), any(LocalDateTime.class));
    }

    // ---------------------------------------------------------------- expiry

    @Test
    @DisplayName("a token is still usable one second before it expires")
    void tokenIsUsableJustBeforeExpiry() {
        String raw = "nearly-expired";
        Instant expiry = NOW.plus(REFRESH_TTL);
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, expiry, null));
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(1);
        clock.set(expiry.minusSeconds(1));

        assertThat(service.rotate(raw).refreshToken().rawToken()).isNotBlank();
    }

    @Test
    @DisplayName("an expired token is refused, and its family is deliberately left alone")
    void expiredTokenIsRefusedWithoutKillingTheFamily() {
        String raw = "expired";
        Instant expiry = NOW.plus(REFRESH_TTL);
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, expiry, null));
        clock.set(expiry.plusSeconds(1));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).revokeFamily(anyString(), anyString(), any());
        verify(familyRevoker, never()).revokeFamilyDurably(anyString(), any(), any());
        verify(refreshTokenRepository, never()).revokeIfLive(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("expiry is exclusive: a token is dead exactly at its expiry instant")
    void expiryIsExclusive() {
        String raw = "boundary";
        Instant expiry = NOW.plus(REFRESH_TTL);
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, expiry, null));
        clock.set(expiry);

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // ------------------------------------------------------------ account state

    @Test
    @DisplayName("a deactivated account cannot refresh, and its session is ended durably")
    void deactivatedAccountCannotRefresh() {
        String raw = "token";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));
        user.setActive(false);

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(familyRevoker).revokeFamilyDurably(
                eq("family-A"), eq(RevocationReason.ACCOUNT_DEACTIVATED), any(LocalDateTime.class));
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("the account is checked before the token is spent, so no lock blocks the revoker")
    void accountIsCheckedBeforeSpendingTheToken() {
        String raw = "token";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));
        user.setActive(false);

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never())
                .revokeIfLive(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("an account with no password set cannot refresh")
    void accountWithoutPasswordCannotRefresh() {
        String raw = "token";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(1);
        user.setPasswordHash(null);

        assertThatThrownBy(() -> service.rotate(raw))
                .describedAs("V13 defines a null hash as 'cannot authenticate'; refresh must honour "
                        + "that too, or it would be a way around it")
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    @DisplayName("a token naming a deleted user is refused")
    void tokenForDeletedUserIsRefused() {
        String raw = "orphan";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));
        when(refreshTokenRepository.revokeIfLive(eq(1L), anyString(), any())).thenReturn(1);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // ---------------------------------------------------------------- logout

    @Test
    @DisplayName("logout revokes the family the presented token belongs to")
    void logoutRevokesTheFamily() {
        String raw = "session-token";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL), null));

        service.logout(raw);

        verify(refreshTokenRepository).revokeFamily(
                eq("family-A"), eq(RevocationReason.LOGOUT.name()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("logout with an unknown token succeeds silently and changes nothing")
    void logoutWithUnknownTokenIsSilent() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.logout("never-issued");

        verify(refreshTokenRepository, never()).revokeFamily(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("logging out twice is idempotent")
    void logoutIsIdempotent() {
        String raw = "already-out";
        givenStored(raw, storedToken(1L, raw, "family-A", NOW, NOW.plus(REFRESH_TTL),
                NOW.plusSeconds(5)));

        service.logout(raw);

        verify(refreshTokenRepository, never()).revokeFamily(anyString(), anyString(), any());
    }

    // ------------------------------------------------------- revoke all sessions

    @Test
    @DisplayName("revoking all sessions bumps the token version, which is what kills access tokens")
    void revokeAllSessionsBumpsTokenVersion() {
        when(refreshTokenRepository.revokeAllForUser(eq(7L), anyString(), any())).thenReturn(2);

        int revoked = service.revokeAllSessions(user, RevocationReason.PASSWORD_CHANGED);

        assertThat(revoked).isEqualTo(2);
        assertThat(user.getTokenVersion())
                .describedAs("revoking refresh tokens alone would leave already-issued access tokens "
                        + "valid for the rest of their TTL")
                .isEqualTo(4);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("revoking all sessions invalidates the cached state, so it takes effect at once")
    void revokeAllSessionsInvalidatesTheCache() {
        service.revokeAllSessions(user, RevocationReason.ACCOUNT_DEACTIVATED);

        verify(principalStateRegistry)
                .invalidate(7L);
    }

    @Test
    @DisplayName("the cache is cleared again after the transaction completes, closing the race window")
    void revokeAllSessionsInvalidatesAgainAfterCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.revokeAllSessions(user, RevocationReason.LOGOUT_ALL);

            assertThat(TransactionSynchronizationManager.getSynchronizations())
                    .describedAs("""
                            The new token version is invisible to other transactions until commit, so \
                            a concurrent request arriving between the invalidation and the commit would \
                            re-cache the OLD version - and the revoked token would keep working for up \
                            to the cache TTL.""")
                    .hasSize(1);

            // Fire the hook as the transaction manager would, and prove it clears the entry again.
            org.mockito.Mockito.clearInvocations(principalStateRegistry);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

            verify(principalStateRegistry).invalidate(7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a rollback also clears the cache, since a stale entry is never the safe outcome")
    void rollbackAlsoInvalidates() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.revokeAllSessions(user, RevocationReason.LOGOUT_ALL);
            org.mockito.Mockito.clearInvocations(principalStateRegistry);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            verify(principalStateRegistry)
                    .invalidate(7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("with no transaction active it still invalidates, and registers nothing")
    void worksWithoutAnActiveTransaction() {
        service.revokeAllSessions(user, RevocationReason.LOGOUT_ALL);

        verify(principalStateRegistry).invalidate(7L);
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test
    @DisplayName("the reason is recorded, so housekeeping is distinguishable from a real revocation")
    void revocationReasonIsRecorded() {
        service.revokeAllSessions(user, RevocationReason.ROLE_CHANGED);

        verify(refreshTokenRepository).revokeAllForUser(
                eq(7L), eq(RevocationReason.ROLE_CHANGED.name()), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("a null token version is treated as 0 rather than failing")
    void nullTokenVersionIsTolerated() {
        user.setTokenVersion(null);

        service.revokeAllSessions(user, RevocationReason.LOGOUT_ALL);

        assertThat(user.getTokenVersion()).isEqualTo(1);
    }

    // ------------------------------------------------------------- session cap

    @Test
    @DisplayName("passing the session cap revokes the oldest sessions, never refuses the new one")
    void sessionCapRevokesOldestSessions() {
        service = new PersistentRefreshTokenService(
                refreshTokenRepository, userRepository, new RefreshTokenGenerator(),
                new RefreshTokenHasher(), principalStateRegistry, familyRevoker,
                properties(REFRESH_TTL, 2), clock);

        when(refreshTokenRepository.countLiveForUser(7L)).thenReturn(3L);
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNullOrderByIssuedAtAscIdAsc(7L))
                .thenReturn(List.of(
                        storedToken(1L, "oldest", "family-old", NOW.minusSeconds(300),
                                NOW.plus(REFRESH_TTL), null),
                        storedToken(2L, "middle", "family-mid", NOW.minusSeconds(200),
                                NOW.plus(REFRESH_TTL), null),
                        storedToken(3L, "newest", "family-new", NOW, NOW.plus(REFRESH_TTL), null)));

        RefreshTokenService.IssuedRefreshToken issued = service.issueFor(user);

        assertThat(issued.rawToken())
                .describedAs("a legitimate new sign-in must never be blocked by stale sessions on "
                        + "devices the user no longer has")
                .isNotBlank();
        verify(refreshTokenRepository).revokeFamily(
                eq("family-old"), eq(RevocationReason.SESSION_CAP.name()), any(LocalDateTime.class));
        verify(refreshTokenRepository, never()).revokeFamily(
                eq("family-new"), anyString(), any());
    }

    @Test
    @DisplayName("a cap of zero means no cap, and nothing is revoked")
    void zeroCapDisablesTheLimit() {
        service = new PersistentRefreshTokenService(
                refreshTokenRepository, userRepository, new RefreshTokenGenerator(),
                new RefreshTokenHasher(), principalStateRegistry, familyRevoker,
                properties(REFRESH_TTL, 0), clock);
        when(refreshTokenRepository.countLiveForUser(7L)).thenReturn(500L);

        service.issueFor(user);

        verify(refreshTokenRepository, never()).revokeFamily(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("staying within the cap revokes nothing")
    void withinCapRevokesNothing() {
        when(refreshTokenRepository.countLiveForUser(7L)).thenReturn(10L);

        service.issueFor(user);

        verify(refreshTokenRepository, never()).revokeFamily(anyString(), anyString(), any());
    }

    // ---------------------------------------------------------------- helpers

    private void givenStored(String rawToken, RefreshToken stored) {
        when(refreshTokenRepository.findByTokenHash(new RefreshTokenHasher().hash(rawToken)))
                .thenReturn(Optional.of(stored));
    }

    private RefreshToken capturedToken() {
        List<RefreshToken> all = capturedTokens();
        return all.get(all.size() - 1);
    }

    private List<RefreshToken> capturedTokens() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static RefreshToken storedToken(Long id, String rawToken, String familyId,
                                            Instant issuedAt, Instant expiresAt, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setId(id);
        token.setTokenHash(new RefreshTokenHasher().hash(rawToken));
        token.setUserId(7L);
        token.setFamilyId(familyId);
        token.setIssuedAt(LocalDateTime.ofInstant(issuedAt, ZoneOffset.UTC));
        token.setExpiresAt(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        if (revokedAt != null) {
            token.setRevokedAt(LocalDateTime.ofInstant(revokedAt, ZoneOffset.UTC));
            token.setRevokedReason(RevocationReason.ROTATED.name());
        }
        return token;
    }

    private static User user(Long id, Role role, boolean active, Integer tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEmployeeCode(1000L + id);
        user.setFullName("User " + id);
        user.setEmail("user" + id + "@example.test");
        user.setRole(role);
        user.setActive(active);
        user.setTokenVersion(tokenVersion);
        user.setPasswordHash("{bcrypt}not-a-real-hash");
        return user;
    }

    private static SecurityProperties properties(Duration refreshTtl, int maxSessions) {
        return new SecurityProperties(
                null,
                new SecurityProperties.Session(refreshTtl, Duration.ofSeconds(15),
                        Duration.ofDays(7), 0, maxSessions, null),
                null, null, null, false);
    }

    /** A clock a test can move, so expiry rules are provable without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
