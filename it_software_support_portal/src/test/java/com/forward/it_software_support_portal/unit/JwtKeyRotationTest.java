package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.security.JwtKeys;
import com.forward.it_software_support_portal.security.SecurityProperties;
import com.forward.it_software_support_portal.security.session.RefreshTokenGenerator;
import com.forward.it_software_support_portal.security.session.RefreshTokenHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing-key configuration and refresh-token cryptography - the parts that are pure functions and can
 * therefore be pinned precisely, with no database and no Docker.
 */
@DisplayName("Token cryptography and key configuration")
class JwtKeyRotationTest {

    private static final String SECRET_A = "a-signing-secret-of-at-least-32-bytes-aaaa";
    private static final String SECRET_B = "a-different-secret-of-at-least-32-bytes-bb";

    @Nested
    @DisplayName("signing keys")
    class SigningKeys {

        @Test
        @DisplayName("a single configured secret produces one key with the configured id")
        void singleKey() {
            JwtKeys keys = JwtKeys.from(jwt(SECRET_A, "2026-10", List.of()));

            assertThat(keys.activeKeyId()).isEqualTo("2026-10");
            assertThat(keys.keyCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a retained previous key is accepted for verification but is not the active one")
        void previousKeyIsRetainedForVerification() {
            JwtKeys keys = JwtKeys.from(jwt(SECRET_B, "2026-10",
                    List.of(new SecurityProperties.JwtKey("2026-04", SECRET_A))));

            assertThat(keys.activeKeyId())
                    .describedAs("new tokens must be signed with the new key only")
                    .isEqualTo("2026-10");
            assertThat(keys.keyCount())
                    .describedAs("tokens signed before the rotation must still verify, or rotating "
                            + "would sign every user out")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("no configured secret still yields a usable key, so local development works")
        void unconfiguredSecretFallsBackToAnEphemeralKey() {
            JwtKeys keys = JwtKeys.from(jwt(null, null, List.of()));

            assertThat(keys.keyCount()).isEqualTo(1);
            assertThat(keys.activeKeyId()).isEqualTo("primary");
        }

        @Test
        @DisplayName("a short active secret is refused at startup, not quietly accepted")
        void shortActiveSecretIsRefused() {
            assertThatThrownBy(() -> JwtKeys.from(jwt("too-short", "k1", List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 bytes");
        }

        @Test
        @DisplayName("a short retained secret is refused too - it can still verify a signature")
        void shortPreviousSecretIsRefused() {
            assertThatThrownBy(() -> JwtKeys.from(jwt(SECRET_A, "k2",
                    List.of(new SecurityProperties.JwtKey("k1", "short")))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least 32 bytes");
        }

        @Test
        @DisplayName("a duplicate key id is refused, because kid is how a token selects its key")
        void duplicateKeyIdIsRefused() {
            assertThatThrownBy(() -> JwtKeys.from(jwt(SECRET_A, "k1",
                    List.of(new SecurityProperties.JwtKey("k1", SECRET_B)))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unique");
        }

        @Test
        @DisplayName("a retained entry missing its id or secret is refused rather than skipped")
        void incompletePreviousKeyIsRefused() {
            assertThatThrownBy(() -> JwtKeys.from(jwt(SECRET_A, "k2",
                    List.of(new SecurityProperties.JwtKey("k1", "  ")))))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> JwtKeys.from(jwt(SECRET_A, "k2",
                    List.of(new SecurityProperties.JwtKey(null, SECRET_B)))))
                    .isInstanceOf(IllegalStateException.class);
        }

        private SecurityProperties.Jwt jwt(String secret, String keyId,
                                           List<SecurityProperties.JwtKey> previous) {
            return new SecurityProperties.Jwt(secret, keyId, previous, null, Duration.ofMinutes(30));
        }
    }

    @Nested
    @DisplayName("refresh-token hashing")
    class Hashing {

        private final RefreshTokenHasher hasher = new RefreshTokenHasher();

        @Test
        @DisplayName("the hash is 64 lowercase hex characters")
        void hashShape() {
            assertThat(hasher.hash("some-token")).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("hashing is deterministic, which is what makes the unique-index lookup possible")
        void hashIsDeterministic() {
            assertThat(hasher.hash("some-token"))
                    .describedAs("a salted hash would force a scan of every row to find a token")
                    .isEqualTo(hasher.hash("some-token"));
        }

        @Test
        @DisplayName("the hash is not the token, so a database disclosure yields nothing usable")
        void hashIsNotTheToken() {
            assertThat(hasher.hash("some-token")).isNotEqualTo("some-token");
        }

        @Test
        @DisplayName("different tokens hash differently")
        void distinctTokensHashDistinctly() {
            assertThat(hasher.hash("token-a")).isNotEqualTo(hasher.hash("token-b"));
        }

        @Test
        @DisplayName("a blank token is rejected rather than hashed to a usable value")
        void blankTokenIsRejected() {
            assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> hasher.hash("  ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("refresh-token generation")
    class Generation {

        private final RefreshTokenGenerator generator = new RefreshTokenGenerator();

        @Test
        @DisplayName("tokens carry 256 bits of entropy, encoded as 43 base64url characters")
        void tokenCarries256Bits() {
            String token = generator.generate();

            assertThat(token)
                    .describedAs("32 random bytes, base64url without padding - this entropy is the "
                            + "reason the refresh endpoint needs no brute-force throttle")
                    .hasSize(43)
                    .matches("[A-Za-z0-9_-]{43}");
        }

        @Test
        @DisplayName("10,000 generated tokens are all distinct")
        void tokensAreDistinct() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 10_000; i++) {
                seen.add(generator.generate());
            }

            assertThat(seen).hasSize(10_000);
        }

        @Test
        @DisplayName("a token carries no structure a holder could read or tamper with")
        void tokenIsOpaque() {
            String token = generator.generate();

            assertThat(token)
                    .describedAs("unlike a JWT, an opaque handle discloses no user id, no expiry and "
                            + "no claims")
                    .doesNotContain(".")
                    .doesNotContain("{");
        }
    }
}
