package com.forward.it_software_support_portal.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Externalised security configuration. Nothing here has a hardcoded secret.
 *
 * @param jwt            access-token settings
 * @param session        session lifecycle: refresh-token lifetime and revocation latency
 * @param cors           browser origins permitted to call the API
 * @param bootstrapAdmin optional first administrator, so a fresh deployment is usable
 * @param rateLimit      login abuse protection
 * @param swaggerPublic  whether the OpenAPI UI is reachable without authentication
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Jwt jwt,
        Session session,
        Cors cors,
        BootstrapAdmin bootstrapAdmin,
        RateLimit rateLimit,
        boolean swaggerPublic
) {

    public SecurityProperties {
        jwt = jwt == null ? new Jwt(null, null, null, null, null) : jwt;
        session = session == null ? new Session(null, null, null, 0, -1, null) : session;
        cors = cors == null ? new Cors(null, false) : cors;
        bootstrapAdmin = bootstrapAdmin == null ? new BootstrapAdmin(null, null, null) : bootstrapAdmin;
        rateLimit = rateLimit == null
                ? new RateLimit(true, 0, 0, null, null, 0, false)
                : rateLimit;
    }

    /**
     * @param secret       HMAC signing key, at least 32 bytes. Supply it through the environment. When
     *                     blank a random key is generated at startup with a loud warning - secure, but
     *                     tokens do not survive a restart and will not validate across instances.
     * @param keyId        the {@code kid} header written into newly issued tokens
     * @param previousKeys secrets retained only to verify tokens signed before the last rotation, so
     *                     that changing the signing secret does not sign every user out. See
     *                     {@link JwtKeys} for the rotation procedure, including the retirement step
     *                     that makes it a rotation rather than an accumulation.
     * @param issuer       the {@code iss} claim this service puts in tokens and requires when validating
     * @param ttl          how long an access token stays valid
     */
    public record Jwt(
            String secret,
            String keyId,
            List<JwtKey> previousKeys,
            String issuer,
            Duration ttl
    ) {

        public static final int MINIMUM_SECRET_BYTES = 32;

        public Jwt {
            issuer = issuer == null || issuer.isBlank() ? "it-software-support-portal" : issuer;
            ttl = ttl == null ? Duration.ofMinutes(30) : ttl;
            previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
        }

        public boolean hasSecret() {
            return secret != null && !secret.isBlank();
        }

        /**
         * The {@code kid} written into newly issued tokens. A default is supplied rather than left
         * absent so that even an unconfigured deployment issues tokens whose key is resolvable by id;
         * otherwise introducing a second key later would orphan every token already in circulation.
         */
        public String keyIdOrDefault() {
            return keyId == null || keyId.isBlank() ? JwtKeys.DEFAULT_KEY_ID : keyId;
        }

        public List<JwtKey> previousKeysOrEmpty() {
            return previousKeys;
        }
    }

    /**
     * One retired signing key, kept so tokens issued before a rotation keep working until they expire.
     *
     * @param keyId  must match the {@code kid} those tokens were signed with
     * @param secret the retired secret; still length-checked, because it can still verify a signature
     */
    public record JwtKey(String keyId, String secret) {

        public boolean isUsable() {
            return keyId != null && !keyId.isBlank() && secret != null && !secret.isBlank();
        }
    }

    /**
     * Session lifecycle (Phase 7): how long a refresh token lives, and how quickly a revocation takes
     * effect.
     *
     * @param refreshTtl          how long a refresh token remains exchangeable. Long enough that a user
     *                            is not asked for their password daily, short enough that an abandoned
     *                            session dies on its own. Default 7 days.
     * @param stateCacheTtl       how long the per-request revocation check may serve cached account
     *                            state. <strong>Zero disables caching</strong> and reads through on
     *                            every authenticated request, which is the correct setting behind a load
     *                            balancer - see {@code session/CachingPrincipalStateRegistry}. Default
     *                            15s, which bounds only revocations this instance did not perform
     *                            itself; a local revocation is immediate at any TTL.
     * @param expiredRetention    how long an expired token row is kept before the purge deletes it.
     *                            Retention exists so a detected replay stays investigable after the
     *                            tokens involved have expired. Default 7 days.
     * @param stateCacheMaxEntries cap on cached accounts, bounding memory. Default 10,000.
     * @param maxSessionsPerUser  cap on simultaneous live refresh tokens per user; the oldest are
     *                            revoked once the cap is passed. Bounds how many devices one leaked
     *                            password can accumulate, and bounds the table. Default 10; 0 means no
     *                            cap.
     * @param purgeCron           when expired token rows are deleted. Default 03:15 daily.
     */
    public record Session(
            Duration refreshTtl,
            Duration stateCacheTtl,
            Duration expiredRetention,
            int stateCacheMaxEntries,
            int maxSessionsPerUser,
            String purgeCron
    ) {

        public Session {
            refreshTtl = refreshTtl == null ? Duration.ofDays(7) : refreshTtl;
            // Zero is a meaningful value here (read through every time), so only null falls back.
            stateCacheTtl = stateCacheTtl == null ? Duration.ofSeconds(15) : stateCacheTtl;
            expiredRetention = expiredRetention == null ? Duration.ofDays(7) : expiredRetention;
            stateCacheMaxEntries = stateCacheMaxEntries <= 0 ? 10_000 : stateCacheMaxEntries;
            // Zero is meaningful (no cap), so only a negative - which Spring never binds - falls back.
            maxSessionsPerUser = maxSessionsPerUser < 0 ? 10 : maxSessionsPerUser;
            purgeCron = purgeCron == null || purgeCron.isBlank() ? "0 15 3 * * *" : purgeCron;
        }
    }

    /**
     * @param allowedOrigins exact origins; never a wildcard
     * @param allowCredentials only needed for cookie-based auth, which this API does not use
     */
    public record Cors(List<String> allowedOrigins, boolean allowCredentials) {

        public Cors {
            allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                    ? List.of("http://localhost:4200")
                    : List.copyOf(allowedOrigins);
        }
    }

    /**
     * Login abuse protection. Applies only to {@code POST /api/auth/login}; authenticated API traffic is
     * untouched.
     *
     * <p>The two thresholds are deliberately different sizes, and the reasoning matters more than the
     * numbers:
     *
     * <ul>
     *   <li>{@code maxAccountFailures = 5} — what actually protects one password. Five is comfortably
     *       above honest mistakes (a typo, an old password, the wrong one of two accounts) and far below
     *       anything useful for guessing: against even a 10,000-entry password list it buys an attacker
     *       0.05% of the list per window.
     *   <li>{@code maxAddressFailures = 20} — looser because addresses are legitimately shared. An office
     *       behind NAT is a single address for everyone in it, so a tight per-address limit would let a
     *       few colleagues fumbling passwords lock out the whole building. Twenty absorbs that while
     *       still stopping a single host spraying many accounts.
     * </ul>
     *
     * <p>{@code window = 15m} with {@code blockDuration = 15m} keeps the block short and
     * self-clearing. An indefinite lockout would hand an attacker a denial-of-service: anyone who knows
     * a colleague's email could keep their account locked forever. Fifteen minutes makes sustained
     * guessing impractical (20 attempts/hour against one account) while capping the damage of a targeted
     * nuisance attack at fifteen minutes.
     *
     * @param enabled               master switch; leave on outside focused testing
     * @param maxAccountFailures    failures within the window before that account is blocked
     * @param maxAddressFailures    failures within the window before that address is blocked
     * @param window                sliding window over which failures are counted
     * @param blockDuration         how long a block lasts once tripped
     * @param maxTrackedKeys        per-dimension cap on tracked keys, bounding memory
     * @param trustForwardedHeaders only enable behind a proxy that appends {@code X-Forwarded-For}
     */
    public record RateLimit(
            boolean enabled,
            int maxAccountFailures,
            int maxAddressFailures,
            Duration window,
            Duration blockDuration,
            int maxTrackedKeys,
            boolean trustForwardedHeaders
    ) {

        public RateLimit {
            maxAccountFailures = maxAccountFailures <= 0 ? 5 : maxAccountFailures;
            maxAddressFailures = maxAddressFailures <= 0 ? 20 : maxAddressFailures;
            window = window == null ? Duration.ofMinutes(15) : window;
            blockDuration = blockDuration == null ? Duration.ofMinutes(15) : blockDuration;
            // 50,000 keys per dimension. Each entry is a short string plus a 24-byte record, so the
            // worst case is a few megabytes - affordable, and far more keys than a real attack sustains
            // inside one window while also fighting the per-address limit.
            maxTrackedKeys = maxTrackedKeys <= 0 ? 50_000 : maxTrackedKeys;
        }
    }

    /**
     * Creates one administrator on startup if, and only if, no user holds the ADMIN role yet.
     *
     * <p>Without this a freshly migrated database is unusable: creating users requires
     * {@code USER_MANAGE}, which requires an account, which requires someone to create it. Disabled
     * unless all three values are supplied, so it can never silently create an account.
     */
    public record BootstrapAdmin(String email, String password, String fullName) {

        public boolean isConfigured() {
            return email != null && !email.isBlank()
                    && password != null && !password.isBlank();
        }

        public String fullNameOrDefault() {
            return fullName == null || fullName.isBlank() ? "Bootstrap Administrator" : fullName;
        }
    }
}
