package com.forward.it_software_support_portal.security.session;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints the opaque refresh tokens handed to clients.
 *
 * <p>32 bytes - 256 bits - from {@link SecureRandom}, base64url-encoded without padding. The token
 * carries no structure, no user id and no claims: it is a bearer handle whose only meaning is the row
 * it matches. That is the point. Nothing can be learned from one, and nothing about it can be forged
 * into something else, because there is nothing in it to tamper with.
 *
 * <p>256 bits is far past any guessing attack: an attacker able to try a million candidates a second
 * for the whole token lifetime still covers an immeasurably small fraction of the space. This is the
 * reason {@code POST /api/auth/refresh} is not throttled the way login is - see docs/SECURITY.md.
 *
 * <p>A single {@code SecureRandom} instance is reused because it is thread-safe and reseeds itself;
 * creating one per call is slower and no stronger.
 */
@Component
public class RefreshTokenGenerator {

    static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
