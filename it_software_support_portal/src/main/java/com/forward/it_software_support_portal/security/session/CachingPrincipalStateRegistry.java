package com.forward.it_software_support_portal.security.session;

import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.repository.projection.PrincipalStateRow;
import com.forward.it_software_support_portal.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Account state for access-token validation, read from the database and cached in process for a short,
 * configurable TTL.
 *
 * <h2>The trade this class exists to make</h2>
 *
 * Checking revocation at all means consulting authoritative state on requests that a self-contained
 * JWT was supposed to answer by itself. Done naively - one query per request - it would add a SQL
 * statement to every authenticated call and break the property Phase 3 measured and Phase 5 preserved:
 * a ticket page costs exactly two statements at any size. Not done at all, revocation does not exist,
 * which is the Phase 4/5 limitation this phase was opened to close.
 *
 * <p>The resolution is a cache with a TTL plus <strong>write-through invalidation</strong>. Every code
 * path that revokes - password change, password reset, deactivation, role change, sign out
 * everywhere - calls {@link #invalidate(Long)} inside the same request, so on a single instance
 * revocation is immediate and the hot path still costs no SQL. The TTL bounds only changes this
 * instance did not make: a direct database edit, or another instance in a multi-instance deployment.
 * Default 15 seconds.
 *
 * <p><strong>Setting the TTL to zero disables caching</strong> and reads through on every request:
 * revocation then becomes immediate cluster-wide, paid for with one indexed two-column select per
 * authenticated request. That is the correct setting for a clustered deployment, and it is a
 * configuration change rather than a code change.
 *
 * <p><strong>No claim is made that this is cluster-wide at the default TTL.</strong> With N instances
 * and a 15-second TTL, a revocation performed on one instance is honoured by the others within 15
 * seconds, not instantly.
 *
 * <h2>Memory</h2>
 *
 * Keys are user ids taken from the {@code sub} claim of a <em>signature-verified</em> token, so unlike
 * the login limiter's keys they cannot be invented by an attacker: the map is bounded by the number of
 * real accounts that actually sign in. A cap is applied anyway, because "bounded by the user table" is
 * not the same as "small", and an evicted entry costs one reload to rebuild.
 */
@Component
public class CachingPrincipalStateRegistry implements PrincipalStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(CachingPrincipalStateRegistry.class);

    private final UserRepository userRepository;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public CachingPrincipalStateRegistry(UserRepository userRepository,
                                         SecurityProperties properties,
                                         Clock clock) {
        this.userRepository = userRepository;
        this.ttl = properties.session().stateCacheTtl();
        this.maxEntries = properties.session().stateCacheMaxEntries();
        this.clock = clock;
        if (ttl.isZero()) {
            log.info("Access-token revocation checks will read through to the database on every "
                    + "authenticated request (app.security.session.state-cache-ttl=0). Revocation is "
                    + "then immediate across all instances, at one select per request.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrincipalState> lookup(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        if (ttl.isZero()) {
            return load(userId);
        }

        Instant now = clock.instant();
        Entry cached = cache.get(userId);
        if (cached != null && cached.isFreshAt(now, ttl)) {
            return Optional.ofNullable(cached.state());
        }

        Optional<PrincipalState> loaded = load(userId);
        // A miss is cached too. Otherwise a token naming a deleted account would hit the database on
        // every request, which would make a stale token a cheap way to generate query load.
        store(userId, new Entry(loaded.orElse(null), now));
        return loaded;
    }

    @Override
    public void invalidate(Long userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    /** Drops every cached entry. Exposed for test isolation, as the login limiter's reset is. */
    public void clearAll() {
        cache.clear();
    }

    /** Current entry count. Exposed so the memory bound can be asserted rather than assumed. */
    public int cachedEntryCount() {
        return cache.size();
    }

    private Optional<PrincipalState> load(Long userId) {
        return userRepository.findPrincipalState(userId)
                .map(CachingPrincipalStateRegistry::toState);
    }

    private static PrincipalState toState(PrincipalStateRow row) {
        // active is nullable in the schema - V1 declared it with a default, not NOT NULL. A null flag
        // counts as inactive: fail closed, exactly as the login path already does.
        int version = row.tokenVersion() == null ? 0 : row.tokenVersion();
        return new PrincipalState(version, Boolean.TRUE.equals(row.active()));
    }

    private void store(Long userId, Entry entry) {
        cache.put(userId, entry);
        if (cache.size() > maxEntries) {
            evictOldest();
        }
    }

    /**
     * Eviction, not rejection. Refusing to cache once full would silently turn every request into a
     * database read; dropping the oldest entries costs one reload each.
     */
    private void evictOldest() {
        int excess = cache.size() - maxEntries;
        if (excess <= 0) {
            return;
        }
        List<Map.Entry<Long, Entry>> byAge = new ArrayList<>(cache.entrySet());
        byAge.sort(Comparator.comparing(entry -> entry.getValue().loadedAt()));
        for (int i = 0; i < excess && i < byAge.size(); i++) {
            cache.remove(byAge.get(i).getKey(), byAge.get(i).getValue());
        }
    }

    /** @param state null means "looked up, and there is no such user" - a cached negative result. */
    private record Entry(PrincipalState state, Instant loadedAt) {

        boolean isFreshAt(Instant now, Duration ttl) {
            return loadedAt.plus(ttl).isAfter(now);
        }
    }
}
