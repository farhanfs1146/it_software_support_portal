package com.forward.it_software_support_portal.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The set of HMAC keys this application will accept, and the one it signs with.
 *
 * <h2>The problem this solves</h2>
 *
 * Until Phase 7 there was exactly one signing secret. That is fine until the day it has to change -
 * because it leaked, because an employee with access to it left, or simply because a rotation policy
 * says so. With one key, changing it invalidates every outstanding token at once: every signed-in user
 * is thrown out, and every client gets a 401 it did not expect. The practical result of that cost is
 * that the secret never gets rotated, which is the worst outcome available.
 *
 * <h2>How rotation works here</h2>
 *
 * Every token is signed with a {@code kid} header naming the key used. The decoder selects the
 * verification key by that {@code kid} from this set. So:
 *
 * <ol>
 *   <li>Add the new secret as {@code app.security.jwt.secret} with a new
 *       {@code app.security.jwt.key-id}, and move the <em>old</em> secret and its id into
 *       {@code app.security.jwt.previous-keys}.</li>
 *   <li>Restart. New tokens are signed with the new key; tokens already in circulation still verify
 *       against the old one, so nobody is signed out.</li>
 *   <li>Once the longest access-token TTL has elapsed, drop the old entry from
 *       {@code previous-keys}. From that moment the old secret can verify nothing.</li>
 * </ol>
 *
 * <p>The retirement step is what makes this a rotation rather than an accumulation. Keeping an old key
 * in {@code previous-keys} forever would mean a leaked secret stays usable forever - so the overlap is
 * deliberately short, and the only reason it exists is to avoid the mass sign-out.
 *
 * <h2>Behaviour when nothing is configured</h2>
 *
 * A random key is generated for this JVM, exactly as before this class existed, with the same warning.
 * Tokens then do not survive a restart and do not validate on another instance - which is secure, and
 * unsuitable for anything but local development.
 *
 * <p><strong>Every key is length-checked.</strong> HS256 takes a key of any length, but one shorter
 * than its 256-bit output weakens the signature, so a short secret is rejected at startup rather than
 * silently accepted. A previous key is held to the same standard: it is still a key that can verify a
 * token.
 */
public final class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    /** Used when no key id is configured, so that even the default case carries a resolvable kid. */
    static final String DEFAULT_KEY_ID = "primary";

    private final String activeKeyId;
    private final JWKSet jwkSet;

    private JwtKeys(String activeKeyId, JWKSet jwkSet) {
        this.activeKeyId = activeKeyId;
        this.jwkSet = jwkSet;
    }

    public static JwtKeys from(SecurityProperties.Jwt jwt) {
        List<JWK> keys = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        String activeKeyId = jwt.keyIdOrDefault();
        keys.add(secretKey(activeKeyId, activeSecret(jwt)));
        seenIds.add(activeKeyId);

        for (SecurityProperties.JwtKey previous : jwt.previousKeysOrEmpty()) {
            if (!previous.isUsable()) {
                throw new IllegalStateException(
                        "app.security.jwt.previous-keys contains an entry with a blank key-id or "
                                + "secret. Remove the entry, or supply both values.");
            }
            if (!seenIds.add(previous.keyId())) {
                throw new IllegalStateException(
                        "app.security.jwt.previous-keys reuses key-id '" + previous.keyId()
                                + "'. Each key id must be unique, including the active one, because the "
                                + "kid header is how a token selects its verification key.");
            }
            keys.add(secretKey(previous.keyId(), previous.secret().getBytes(StandardCharsets.UTF_8)));
        }

        if (keys.size() > 1) {
            log.info("Accepting access tokens signed with {} keys (active '{}', {} retained for "
                            + "rotation). Remove retained keys once no token signed with them can still "
                            + "be within its {} lifetime.",
                    keys.size(), activeKeyId, keys.size() - 1, jwt.ttl());
        }

        return new JwtKeys(activeKeyId, new JWKSet(keys));
    }

    /** The {@code kid} written into the header of every newly issued token. */
    public String activeKeyId() {
        return activeKeyId;
    }

    /** Every key a token may be verified against: the active one plus any retained for rotation. */
    public JWKSet jwkSet() {
        return jwkSet;
    }

    public int keyCount() {
        return jwkSet.getKeys().size();
    }

    private static byte[] activeSecret(SecurityProperties.Jwt jwt) {
        if (jwt.hasSecret()) {
            return jwt.secret().getBytes(StandardCharsets.UTF_8);
        }
        byte[] random = new byte[SecurityProperties.Jwt.MINIMUM_SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        log.warn("""
                app.security.jwt.secret is not configured. A random signing key was generated for this \
                JVM. Access tokens will be invalidated by a restart and will not validate on another \
                instance. Set app.security.jwt.secret (for example from the APP_JWT_SECRET environment \
                variable) in any shared or production environment.""");
        return random;
    }

    private static OctetSequenceKey secretKey(String keyId, byte[] secret) {
        if (secret.length < SecurityProperties.Jwt.MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "The signing secret for key '" + keyId + "' must be at least "
                            + SecurityProperties.Jwt.MINIMUM_SECRET_BYTES + " bytes for "
                            + SecurityConfig.JWS_ALGORITHM.getName() + "; it is " + secret.length
                            + ". Configure a longer secret via the environment.");
        }
        return new OctetSequenceKey.Builder(
                new SecretKeySpec(secret, SecurityConfig.JWS_ALGORITHM.getName()))
                .keyID(keyId)
                .build();
    }
}
