package com.forward.it_software_support_portal.security.ratelimit;

import com.forward.it_software_support_portal.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process, sliding-window login throttle with two independent dimensions.
 *
 * <h2>Two dimensions, and why both are needed</h2>
 *
 * Either one alone is bypassable:
 *
 * <ul>
 *   <li><strong>Per-account only</strong> — an attacker spreads attempts across many addresses and the
 *       per-address budget never applies. Account throttling is what actually protects a single
 *       password.
 *   <li><strong>Per-address only</strong> — an attacker rotates addresses (a botnet, or simply a cloud
 *       provider) and each fresh address gets a full budget against the same account.
 * </ul>
 *
 * So both are enforced, and the first to trip refuses the attempt. The per-address budget is the looser
 * of the two because addresses are legitimately shared: an office behind NAT is one address for
 * everybody, and a handful of colleagues mistyping passwords must not lock the building out.
 *
 * <h2>Counting the submitted string, not the resolved account</h2>
 *
 * The account dimension is keyed on the <em>email the caller submitted</em>, normalised, whether or not
 * it matches a real user. This is deliberate and it is a security property, not an implementation
 * convenience: if only real accounts were throttled, then a 429 would prove an account exists and the
 * limiter would hand back exactly the enumeration oracle that the uniform 401 was built to remove.
 * Normalisation (trim, lower-case) stops {@code Admin@x.test} and {@code admin@x.test} being counted as
 * two different budgets.
 *
 * <h2>Memory</h2>
 *
 * Keys are attacker-supplied, so unbounded growth would be a denial-of-service vector in a component
 * whose job is to prevent one. Two bounds apply:
 *
 * <ul>
 *   <li>Entries expire: anything whose window and block have both elapsed is dead weight and is swept.
 *   <li>A hard cap on entry count. When exceeded, expired entries are swept first and, if still over,
 *       the entries with the oldest windows are evicted.
 * </ul>
 *
 * Eviction rather than rejection is the deliberate choice. Refusing to track new keys once full would
 * let an attacker fill the map with junk and then either evade throttling entirely (if we fail open) or
 * deny login to everyone (if we fail closed). Eviction degrades gracefully instead: with the default cap
 * an attacker would need to sustain tens of thousands of distinct keys inside the window to start
 * displacing real entries, and would be fighting the per-address limit the whole time.
 *
 * <h2>Thread safety</h2>
 *
 * All mutation happens inside {@link ConcurrentHashMap#compute}, so each key's update is atomic under
 * the map's own per-bin lock. The stored value is an immutable record, so nothing can be observed
 * half-updated. There is no {@code synchronized} block and no non-thread-safe collection.
 */
@Component
public class InMemoryLoginAttemptLimiter implements LoginAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryLoginAttemptLimiter.class);

    /** Immutable so a concurrent reader can never see a partially updated counter. */
    private record Attempts(int failures, long windowStartMillis, long blockedUntilMillis) {
    }

    private final Map<String, Attempts> accountAttempts = new ConcurrentHashMap<>();
    private final Map<String, Attempts> addressAttempts = new ConcurrentHashMap<>();

    private final SecurityProperties.RateLimit config;
    private final Clock clock;

    /** The constructor Spring uses. Annotated because a second one exists for tests. */
    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryLoginAttemptLimiter(SecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Clock-injecting constructor, so window and block expiry can be tested without sleeping. */
    InMemoryLoginAttemptLimiter(SecurityProperties properties, Clock clock) {
        this.config = properties.rateLimit();
        this.clock = clock;
    }

    @Override
    public void checkAllowed(String email, String clientIp) {
        if (!config.enabled()) {
            return;
        }
        long now = clock.millis();

        // Address first: it is the cheaper check and the broader signal.
        long addressRetry = blockedSecondsRemaining(addressAttempts.get(addressKey(clientIp)), now);
        long accountRetry = blockedSecondsRemaining(accountAttempts.get(accountKey(email)), now);

        long retryAfter = Math.max(addressRetry, accountRetry);
        if (retryAfter > 0) {
            // Logged, never returned: the response must not say which dimension tripped, because
            // "your account is locked" and "your address is blocked" are different facts about
            // whether the account exists.
            log.info("Login refused by rate limit: addressBlockedFor={}s accountBlockedFor={}s",
                    addressRetry, accountRetry);
            throw new TooManyLoginAttemptsException(retryAfter);
        }
    }

    @Override
    public void recordFailure(String email, String clientIp) {
        if (!config.enabled()) {
            return;
        }
        long now = clock.millis();
        registerFailure(accountAttempts, accountKey(email), config.maxAccountFailures(), now);
        registerFailure(addressAttempts, addressKey(clientIp), config.maxAddressFailures(), now);
        enforceCapacity(accountAttempts, now);
        enforceCapacity(addressAttempts, now);
    }

    /**
     * Clears failure state after a successful authentication, so earlier fumbles cannot accumulate and
     * punish someone who then logs in correctly.
     *
     * <p>A success can only reach here when the attempt was not blocked - {@code checkAllowed} runs
     * first and throws - so this cannot be used to clear an active block.
     */
    @Override
    public void recordSuccess(String email, String clientIp) {
        if (!config.enabled()) {
            return;
        }
        accountAttempts.remove(accountKey(email));
        addressAttempts.remove(addressKey(clientIp));
    }

    // ------------------------------------------------------------------ internals

    private void registerFailure(Map<String, Attempts> store, String key, int maxFailures, long now) {
        long windowMillis = config.window().toMillis();
        long blockMillis = config.blockDuration().toMillis();

        store.compute(key, (ignored, current) -> {
            if (current == null || now - current.windowStartMillis() >= windowMillis) {
                // Fresh window. Also the path taken once a previous block has aged out, which is what
                // makes the block temporary rather than sticky.
                return new Attempts(1, now, 0L);
            }
            int failures = current.failures() + 1;
            if (failures >= maxFailures) {
                // Window restarts with the block so that, once it expires, the caller gets a clean
                // budget instead of being re-blocked by the same stale counter.
                return new Attempts(failures, now, now + blockMillis);
            }
            return new Attempts(failures, current.windowStartMillis(), current.blockedUntilMillis());
        });
    }

    private long blockedSecondsRemaining(Attempts attempts, long now) {
        if (attempts == null || attempts.blockedUntilMillis() <= now) {
            return 0L;
        }
        // Round up: reporting 0 seconds while still blocked would invite an immediate pointless retry.
        return Math.max(1L, (attempts.blockedUntilMillis() - now + 999L) / 1000L);
    }

    private boolean isDead(Attempts attempts, long now) {
        long windowMillis = config.window().toMillis();
        return attempts.blockedUntilMillis() <= now
                && now - attempts.windowStartMillis() >= windowMillis;
    }

    private void enforceCapacity(Map<String, Attempts> store, long now) {
        int max = config.maxTrackedKeys();
        if (store.size() <= max) {
            return;
        }
        store.entrySet().removeIf(entry -> isDead(entry.getValue(), now));
        if (store.size() <= max) {
            return;
        }
        // Still over capacity: shed the oldest windows down to the cap. Only reachable under a
        // deliberate high-cardinality attack, so the sort cost is acceptable and bounded.
        List<Map.Entry<String, Attempts>> oldestFirst = new ArrayList<>(store.entrySet());
        oldestFirst.sort(Comparator.comparingLong(entry -> entry.getValue().windowStartMillis()));
        int toEvict = store.size() - max;
        for (int i = 0; i < toEvict && i < oldestFirst.size(); i++) {
            store.remove(oldestFirst.get(i).getKey(), oldestFirst.get(i).getValue());
        }
        log.warn("Login attempt tracker exceeded {} keys and evicted {} of the oldest entries. "
                + "This usually means a high-cardinality credential-stuffing attempt.", max, toEvict);
    }

    private static String accountKey(String email) {
        return "account:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String clientIp) {
        return "address:" + (clientIp == null ? "unknown" : clientIp);
    }

    /**
     * Discards all tracked attempts.
     *
     * <p>Two legitimate uses. Operationally, it clears every active block at once - the blunt remedy if
     * a misconfigured threshold starts locking real users out. In tests, it keeps methods independent:
     * the limiter is a singleton for the lifetime of the Spring context, so without this, failures from
     * one test would count towards the next test's budget and produce 429s that have nothing to do with
     * the behaviour under test.
     */
    public void clearAll() {
        accountAttempts.clear();
        addressAttempts.clear();
    }

    // --------------------------------------------------------- test-visible state

    int trackedAccountKeys() {
        return accountAttempts.size();
    }

    int trackedAddressKeys() {
        return addressAttempts.size();
    }
}
