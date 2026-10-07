package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.repository.projection.PrincipalStateRow;
import com.forward.it_software_support_portal.security.JwtClaims;
import com.forward.it_software_support_portal.security.SecurityProperties;
import com.forward.it_software_support_portal.security.session.AccessTokenRevocationValidator;
import com.forward.it_software_support_portal.security.session.CachingPrincipalStateRegistry;
import com.forward.it_software_support_portal.security.session.PrincipalState;
import com.forward.it_software_support_portal.security.session.PrincipalStateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The revocation check on the authenticated request path: the validator's decisions, and the cache
 * that governs how often the database is consulted.
 *
 * <p>Both halves are verified here, Docker-free, with an injected clock - the TTL rules are statements
 * about time and would otherwise need the test to sleep.
 */
@DisplayName("Access-token revocation")
class AccessTokenRevocationTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("the validator's verdicts")
    class ValidatorVerdicts {

        @Mock
        private PrincipalStateRegistry registry;

        private AccessTokenRevocationValidator validator;

        @BeforeEach
        void setUp() {
            validator = new AccessTokenRevocationValidator(registry);
        }

        @Test
        @DisplayName("a matching token version on an active account passes")
        void matchingVersionPasses() {
            when(registry.lookup(7L)).thenReturn(Optional.of(new PrincipalState(4, true)));

            assertThat(validator.validate(token("7", 4)).hasErrors()).isFalse();
        }

        @Test
        @DisplayName("a superseded token version is rejected - this is what revocation means")
        void supersededVersionIsRejected() {
            when(registry.lookup(7L)).thenReturn(Optional.of(new PrincipalState(5, true)));

            assertThat(validator.validate(token("7", 4))).matches(OAuth2TokenValidatorResult::hasErrors);
        }

        @Test
        @DisplayName("a deactivated account is rejected even though the version still matches")
        void deactivatedAccountIsRejected() {
            when(registry.lookup(7L)).thenReturn(Optional.of(new PrincipalState(4, false)));

            assertThat(validator.validate(token("7", 4)).hasErrors())
                    .describedAs("this closes the window even when a row was edited directly in the "
                            + "database and nobody bumped the counter")
                    .isTrue();
        }

        @Test
        @DisplayName("a token for a deleted account is rejected")
        void deletedAccountIsRejected() {
            when(registry.lookup(7L)).thenReturn(Optional.empty());

            assertThat(validator.validate(token("7", 4)).hasErrors()).isTrue();
        }

        @Test
        @DisplayName("a token with no version claim is rejected, not waved through")
        void missingVersionClaimIsRejected() {
            Jwt jwt = jwt("7", new HashMap<>());

            assertThat(validator.validate(jwt).hasErrors())
                    .describedAs("tokens predating revocation support must fail closed; the cost is "
                            + "one re-login")
                    .isTrue();
            verify(registry, times(0)).lookup(anyLong());
        }

        @Test
        @DisplayName("a version claim arriving as a JSON string is still read")
        void stringVersionClaimIsRead() {
            when(registry.lookup(7L)).thenReturn(Optional.of(new PrincipalState(4, true)));
            Jwt jwt = jwt("7", claims(JwtClaims.TOKEN_VERSION, "4"));

            assertThat(validator.validate(jwt).hasErrors())
                    .describedAs("JSON number typing varies by parser; the claim must not be cast")
                    .isFalse();
        }

        @Test
        @DisplayName("a non-numeric version claim is rejected")
        void unparseableVersionClaimIsRejected() {
            Jwt jwt = jwt("7", claims(JwtClaims.TOKEN_VERSION, "not-a-number"));

            assertThat(validator.validate(jwt).hasErrors()).isTrue();
        }

        @Test
        @DisplayName("a non-numeric, zero or negative subject is rejected")
        void unusableSubjectIsRejected() {
            assertThat(validator.validate(token("not-an-id", 0)).hasErrors()).isTrue();
            assertThat(validator.validate(token("0", 0)).hasErrors()).isTrue();
            assertThat(validator.validate(token("-3", 0)).hasErrors()).isTrue();
        }

        @Test
        @DisplayName("the failure says nothing about why, so a revoked token is not an oracle")
        void failureRevealsNothing() {
            when(registry.lookup(7L)).thenReturn(Optional.of(new PrincipalState(4, false)));
            when(registry.lookup(8L)).thenReturn(Optional.empty());

            String deactivated = validator.validate(token("7", 4))
                    .getErrors().iterator().next().getDescription();
            String deleted = validator.validate(token("8", 4))
                    .getErrors().iterator().next().getDescription();

            assertThat(deactivated)
                    .describedAs("'account disabled' versus 'no such account' would be an "
                            + "enumeration oracle")
                    .isEqualTo(deleted);
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("the state cache")
    class StateCache {

        @Mock
        private UserRepository userRepository;

        private MutableClock clock;

        @BeforeEach
        void setUp() {
            clock = new MutableClock(NOW);
            when(userRepository.findPrincipalState(7L))
                    .thenReturn(Optional.of(new PrincipalStateRow(4, true)));
        }

        @Test
        @DisplayName("a second lookup inside the TTL costs no database query")
        void repeatedLookupIsServedFromCache() {
            CachingPrincipalStateRegistry registry = registry(Duration.ofSeconds(15), 100);

            registry.lookup(7L);
            registry.lookup(7L);
            registry.lookup(7L);

            verify(userRepository, times(1))
                    .findPrincipalState(7L);
        }

        @Test
        @DisplayName("the cache keeps the per-request cost of revocation checking at zero queries")
        void hotPathCostsNoQueries() {
            CachingPrincipalStateRegistry registry = registry(Duration.ofSeconds(15), 100);
            registry.lookup(7L);

            clock.set(NOW.plusSeconds(14));
            registry.lookup(7L);

            verify(userRepository, times(1))
                    .findPrincipalState(7L);
        }

        @Test
        @DisplayName("state is re-read once the TTL elapses")
        void ttlExpiryForcesReload() {
            CachingPrincipalStateRegistry registry = registry(Duration.ofSeconds(15), 100);
            registry.lookup(7L);

            clock.set(NOW.plusSeconds(16));
            registry.lookup(7L);

            verify(userRepository, times(2)).findPrincipalState(7L);
        }

        @Test
        @DisplayName("invalidate() makes a local revocation take effect on the very next request")
        void invalidateTakesEffectImmediately() {
            CachingPrincipalStateRegistry registry = registry(Duration.ofHours(1), 100);
            assertThat(registry.lookup(7L)).contains(new PrincipalState(4, true));

            when(userRepository.findPrincipalState(7L))
                    .thenReturn(Optional.of(new PrincipalStateRow(5, true)));
            registry.invalidate(7L);

            assertThat(registry.lookup(7L))
                    .describedAs("without write-through invalidation, revocation would wait out the "
                            + "TTL even on the instance that performed it")
                    .contains(new PrincipalState(5, true));
        }

        @Test
        @DisplayName("a TTL of zero reads through on every lookup")
        void zeroTtlDisablesCaching() {
            CachingPrincipalStateRegistry registry = registry(Duration.ZERO, 100);

            registry.lookup(7L);
            registry.lookup(7L);
            registry.lookup(7L);

            verify(userRepository, times(3))
                    .findPrincipalState(7L);
            assertThat(registry.cachedEntryCount()).isZero();
        }

        @Test
        @DisplayName("a missing account is cached, so a stale token cannot generate query load")
        void missingAccountIsCached() {
            when(userRepository.findPrincipalState(99L)).thenReturn(Optional.empty());
            CachingPrincipalStateRegistry registry = registry(Duration.ofSeconds(15), 100);

            assertThat(registry.lookup(99L)).isEmpty();
            assertThat(registry.lookup(99L)).isEmpty();

            verify(userRepository, times(1)).findPrincipalState(99L);
        }

        @Test
        @DisplayName("a null active flag is read as inactive - fail closed")
        void nullActiveFlagIsInactive() {
            when(userRepository.findPrincipalState(7L))
                    .thenReturn(Optional.of(new PrincipalStateRow(4, null)));

            assertThat(registry(Duration.ofSeconds(15), 100).lookup(7L))
                    .contains(new PrincipalState(4, false));
        }

        @Test
        @DisplayName("a null token version is read as 0, matching the column default")
        void nullTokenVersionIsZero() {
            when(userRepository.findPrincipalState(7L))
                    .thenReturn(Optional.of(new PrincipalStateRow(null, true)));

            assertThat(registry(Duration.ofSeconds(15), 100).lookup(7L))
                    .contains(new PrincipalState(0, true));
        }

        @Test
        @DisplayName("the cache stays within its cap, evicting rather than refusing")
        void cacheIsBounded() {
            CachingPrincipalStateRegistry registry = registry(Duration.ofHours(1), 10);
            for (long id = 1; id <= 500; id++) {
                when(userRepository.findPrincipalState(id))
                        .thenReturn(Optional.of(new PrincipalStateRow(0, true)));
                clock.set(NOW.plusMillis(id));
                registry.lookup(id);
            }

            assertThat(registry.cachedEntryCount()).isLessThanOrEqualTo(10);
            assertThat(registry.lookup(500L))
                    .describedAs("eviction must not stop the registry answering correctly")
                    .isPresent();
        }

        @Test
        @DisplayName("a null user id is answered without a query")
        void nullUserIdIsRejected() {
            CachingPrincipalStateRegistry registry = registry(Duration.ofSeconds(15), 100);

            assertThat(registry.lookup(null)).isEmpty();

            verify(userRepository, times(0)).findPrincipalState(anyLong());
        }

        private CachingPrincipalStateRegistry registry(Duration ttl, int maxEntries) {
            SecurityProperties properties = new SecurityProperties(
                    null,
                    new SecurityProperties.Session(null, ttl, null, maxEntries, -1, null),
                    null, null, null, false);
            return new CachingPrincipalStateRegistry(userRepository, properties, clock);
        }
    }

    private static Jwt token(String subject, int tokenVersion) {
        return jwt(subject, claims(JwtClaims.TOKEN_VERSION, tokenVersion));
    }

    private static Map<String, Object> claims(String name, Object value) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(name, value);
        return claims;
    }

    private static Jwt jwt(String subject, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.put("sub", subject);
        return new Jwt("header.payload.signature", NOW, NOW.plusSeconds(1800),
                Map.of("alg", "HS256"), claims);
    }

    /** A clock a test can move, so TTL rules are provable without sleeping. */
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
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
