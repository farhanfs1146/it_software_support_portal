package com.forward.it_software_support_portal.security.session;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Turns a plaintext refresh token into the value stored in {@code refresh_tokens.token_hash}.
 *
 * <p><strong>SHA-256, not BCrypt, and that is the considered choice rather than the lazy one.</strong>
 * BCrypt is the right tool for passwords because passwords are low-entropy and human-chosen, so the
 * defence has to be making each guess expensive. A refresh token is 256 bits straight from a CSPRNG:
 * there is no guess space to slow an attacker down in, so a work factor would buy nothing and cost a
 * BCrypt verification on every refresh.
 *
 * <p>There is also a structural reason. BCrypt salts every hash, so the same token hashes differently
 * each time and a lookup would have to load every row and verify against each one - O(rows) per
 * refresh. A deterministic hash is what makes the unique index on {@code token_hash} usable, so a
 * refresh is a single indexed lookup.
 *
 * <p>No salt is needed for the same reason: salting defends against precomputation over a small input
 * space, and a 256-bit random value has none.
 */
@Component
public class RefreshTokenHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** @return the hex-encoded SHA-256 digest of {@code rawToken}, always 64 lowercase characters. */
    public String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Cannot hash a blank refresh token");
        }
        byte[] digest = digest(rawToken.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }

    private static byte[] digest(byte[] input) {
        try {
            // A fresh instance per call: MessageDigest is not thread-safe, and this is not a hot path.
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
