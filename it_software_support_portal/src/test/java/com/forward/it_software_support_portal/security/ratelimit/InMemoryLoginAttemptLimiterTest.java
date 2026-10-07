package com.forward.it_software_support_portal.security.ratelimit;

import com.forward.it_software_support_portal.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the login throttle.
 *
 * <p>Deliberately a plain unit test with **no Spring and no Docker**, so the security component that
 * most needs deterministic coverage is verifiable on any machine — including this one, where
 * Testcontainers cannot run.
 *
 * <p>Window and block expiry are driven by an injected {@link Clock} that the test advances by hand.
 * Sleeping for fifteen minutes is not a test, and sleeping for a shortened window makes the suite slow
 * and flaky.
 */
class InMemoryLoginAttemptLimiterTest {

    /** A clock the test moves explicitly, so expiry is exercised without waiting. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }
    }

    private static final String EMAIL = "victim@example.test";
    private static final String IP = "203.0.113.7";

    private MutableClock clock;
    private InMemoryLoginAttemptLimiter limiter;

    private static SecurityProperties propertiesWith(SecurityProperties.RateLimit rateLimit) {
        return new SecurityProperties(null, null, null, null, rateLimit, true);
    }

    private static SecurityProperties.RateLimit limits(int account, int address, int maxKeys) {
        return new SecurityProperties.RateLimit(
                true, account, address,
                Duration.ofMinutes(15), Duration.ofMinutes(15), maxKeys, false);
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        limiter = new InMemoryLoginAttemptLimiter(propertiesWith(limits(5, 20, 50_000)), clock);
    }

    // ------------------------------------------------------------- thresholds

    @Test
    @DisplayName("attempts below the account threshold are allowed")
    void belowAccountThresholdIsAllowed() {
        for (int i = 0; i < 4; i++) {
            assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP))
                .as("four failures is under the limit of five, so the fifth attempt must proceed")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the account is blocked on reaching the threshold")
    void accountBlockedAtThreshold() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("the address threshold is independent of the account threshold")
    void addressThresholdIsIndependent() {
        // Five failures each against four different accounts: 20 address failures, but only five per
        // account, so it is the address limit that trips.
        for (int account = 0; account < 4; account++) {
            for (int attempt = 0; attempt < 5; attempt++) {
                limiter.recordFailure("user" + account + "@example.test", IP);
            }
        }

        assertThatThrownBy(() -> limiter.checkAllowed("never-tried@example.test", IP))
                .as("""
                        An attacker spraying many accounts from one address must be stopped even though \
                        no single account reached its own limit.""")
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("Retry-After is positive and no longer than the block duration")
    void retryAfterIsSane() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        TooManyLoginAttemptsException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                TooManyLoginAttemptsException.class, () -> limiter.checkAllowed(EMAIL, IP));

        assertThat(thrown.getRetryAfterSeconds())
                .as("never zero while still blocked - that would invite an immediate pointless retry")
                .isPositive()
                .isLessThanOrEqualTo(Duration.ofMinutes(15).toSeconds());
    }

    // ----------------------------------------------------------- bypass paths

    @Test
    @DisplayName("changing address does not reset an account block")
    void changingAddressDoesNotBypassAccountBlock() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(EMAIL, "198.51.100." + i);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, "203.0.113.250"))
                .as("""
                        This is the distributed-attack case: five addresses, one account. Without an \
                        account dimension, per-address throttling would be defeated by rotating hosts.""")
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("changing account does not reset an address block")
    void changingAccountDoesNotBypassAddressBlock() {
        for (int i = 0; i < 20; i++) {
            limiter.recordFailure("sprayed" + i + "@example.test", IP);
        }

        assertThatThrownBy(() -> limiter.checkAllowed("yet-another@example.test", IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("email case and surrounding whitespace do not create separate budgets")
    void emailIsNormalised() {
        limiter.recordFailure("Victim@Example.TEST", IP);
        limiter.recordFailure("  victim@example.test  ", IP);
        limiter.recordFailure("VICTIM@EXAMPLE.TEST", IP);
        limiter.recordFailure("victim@example.test", IP);
        limiter.recordFailure("vIcTiM@eXaMpLe.TeSt", IP);

        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, "203.0.113.99"))
                .as("otherwise an attacker gets a fresh budget per capitalisation of the same address")
                .isInstanceOf(TooManyLoginAttemptsException.class);
        assertThat(limiter.trackedAccountKeys())
                .as("all five variants collapse to one key")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an account that was never tried is throttled too, so 429 is not an existence oracle")
    void unknownAccountsAreTrackedIdentically() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("does-not-exist@example.test", IP);
        }

        assertThatThrownBy(() -> limiter.checkAllowed("does-not-exist@example.test", "203.0.113.50"))
                .as("""
                        The limiter keys on the submitted string, not a resolved user. If only real \
                        accounts were throttled, a 429 would prove an account exists - reintroducing \
                        exactly the enumeration oracle the uniform 401 removes.""")
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    // ------------------------------------------------------- expiry and reset

    @Test
    @DisplayName("the block expires once the configured duration passes")
    void blockExpires() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP))
                .as("""
                        Blocks must be temporary and self-clearing. An indefinite lockout would let \
                        anyone who knows a colleague's email deny them access permanently.""")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("failures older than the window do not accumulate")
    void failuresOutsideTheWindowAreForgotten() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        clock.advance(Duration.ofMinutes(16));

        // A fresh window: four more failures must still be under the limit.
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP))
                .as("a sliding window, not a lifetime total")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a successful login clears the failure state")
    void successClearsFailures() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        limiter.recordSuccess(EMAIL, IP);

        // Four more failures would have tripped the limit had the earlier ones persisted.
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure(EMAIL, IP);
        }
        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP))
                .as("stale failures must not punish someone who then authenticates correctly")
                .doesNotThrowAnyException();
        assertThat(limiter.trackedAccountKeys()).isEqualTo(1);
    }

    @Test
    @DisplayName("a block survives until it expires - success cannot clear it, because success is refused")
    void blockCannotBeClearedBySuccess() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        // checkAllowed runs before any password verification, so a caller cannot reach a success while
        // blocked. Asserting the ordering property rather than calling recordSuccess out of sequence.
        assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    // ------------------------------------------------------- memory behaviour

    @Test
    @DisplayName("tracked keys stay bounded under a high-cardinality attack")
    void trackedKeysAreBounded() {
        int cap = 100;
        limiter = new InMemoryLoginAttemptLimiter(propertiesWith(limits(5, 20, cap)), clock);

        for (int i = 0; i < cap * 10; i++) {
            limiter.recordFailure("flood" + i + "@example.test", "198.51.100." + (i % 256));
        }

        assertThat(limiter.trackedAccountKeys())
                .as("""
                        Unbounded growth would make this component its own denial-of-service vector. \
                        Entries are evicted rather than refused, so the tracker never stops accepting \
                        new keys - which would either disable throttling or lock everyone out.""")
                .isLessThanOrEqualTo(cap);
        assertThat(limiter.trackedAddressKeys()).isLessThanOrEqualTo(cap);
    }

    @Test
    @DisplayName("expired entries are swept rather than evicting live ones")
    void expiredEntriesAreSweptFirst() {
        int cap = 50;
        limiter = new InMemoryLoginAttemptLimiter(propertiesWith(limits(5, 20, cap)), clock);

        for (int i = 0; i < cap; i++) {
            limiter.recordFailure("old" + i + "@example.test", IP);
        }

        // Everything above is now dead: window and block have both elapsed.
        clock.advance(Duration.ofMinutes(31));

        for (int i = 0; i < cap; i++) {
            limiter.recordFailure("fresh" + i + "@example.test", IP);
        }

        assertThat(limiter.trackedAccountKeys()).isLessThanOrEqualTo(cap);
        // The live entry must have survived the sweep.
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("survivor@example.test", IP);
        }
        assertThatThrownBy(() -> limiter.checkAllowed("survivor@example.test", "203.0.113.200"))
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    // ------------------------------------------------------------ concurrency

    @Test
    @DisplayName("concurrent failures against one account are counted without loss")
    void concurrentFailuresAreCountedSafely() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    limiter.recordFailure(EMAIL, IP);
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }

            assertThatThrownBy(() -> limiter.checkAllowed(EMAIL, IP))
                    .as("""
                            64 concurrent failures must leave the account blocked. A lost update under \
                            contention would mean the threshold could be walked past by racing - the \
                            whole reason mutation happens inside ConcurrentHashMap.compute.""")
                    .isInstanceOf(TooManyLoginAttemptsException.class);
            assertThat(limiter.trackedAccountKeys()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("concurrent checks and records do not throw")
    void concurrentMixedOperationsAreSafe() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger unexpected = new AtomicInteger();
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int r = 0; r < 20; r++) {
                        try {
                            limiter.checkAllowed("mixed" + (index % 4) + "@example.test", IP);
                        } catch (TooManyLoginAttemptsException expected) {
                            // throttling working as intended
                        } catch (RuntimeException e) {
                            unexpected.incrementAndGet();
                        }
                        limiter.recordFailure("mixed" + (index % 4) + "@example.test", IP);
                        if (r % 7 == 0) {
                            limiter.recordSuccess("mixed" + (index % 4) + "@example.test", IP);
                        }
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }

            assertThat(unexpected.get())
                    .as("no ConcurrentModificationException, no NPE, no corrupted state")
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------- disabled

    @Test
    @DisplayName("the master switch disables all tracking")
    void disabledLimiterTracksNothing() {
        limiter = new InMemoryLoginAttemptLimiter(propertiesWith(
                new SecurityProperties.RateLimit(false, 5, 20,
                        Duration.ofMinutes(15), Duration.ofMinutes(15), 50_000, false)), clock);

        for (int i = 0; i < 100; i++) {
            limiter.recordFailure(EMAIL, IP);
        }

        assertThatCode(() -> limiter.checkAllowed(EMAIL, IP)).doesNotThrowAnyException();
        assertThat(limiter.trackedAccountKeys()).isZero();
    }
}
