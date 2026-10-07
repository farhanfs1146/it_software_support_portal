package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.entity.User;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Issues the signed, short-lived access tokens that authenticate every API call.
 *
 * <p><strong>What a token carries, and why each item is there.</strong>
 *
 * <ul>
 *   <li>{@code sub} - the user id. The only identity the application trusts; see
 *       {@code AuthenticatedCurrentUserProvider}.</li>
 *   <li>{@code role} - expanded into permission authorities on arrival, so the permission model can
 *       change without reissuing tokens.</li>
 *   <li>{@code email} - informational only. Nothing authorizes on it.</li>
 *   <li>{@code tv} - the account's revocation counter at issue time (Phase 7). This is what makes a
 *       token revocable at all: one increment of {@code users.token_version} invalidates every token
 *       issued before it. See {@code session/AccessTokenRevocationValidator}.</li>
 *   <li>{@code jti} - a unique id per token. Not used for revocation (the counter does that, without a
 *       denylist), but it makes an individual token identifiable in logs and in an investigation
 *       without the token value itself ever being written down.</li>
 *   <li>{@code iss}, {@code iat}, {@code exp} - validated by Spring's default validators.</li>
 * </ul>
 *
 * <p>The signing key is selected by the {@code kid} header, which is what allows the signing secret to
 * be rotated without invalidating tokens signed with the previous one. See {@code JwtKeys}.
 *
 * <p><strong>No password, no permission list and no personal data beyond the email go in.</strong> A
 * JWT is signed, not encrypted: anyone holding one can read every claim.
 */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final JwtKeys jwtKeys;

    public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties, JwtKeys jwtKeys) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.jwtKeys = jwtKeys;
    }

    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.getId()))
                .claim(JwtClaims.EMAIL, user.getEmail())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .claim(JwtClaims.TOKEN_VERSION, tokenVersionOf(user))
                .build();

        JwsHeader header = JwsHeader.with(SecurityConfig.JWS_ALGORITHM)
                .keyId(jwtKeys.activeKeyId())
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(header, claims))
                .getTokenValue();

        return new IssuedToken(token, properties.jwt().ttl().toSeconds());
    }

    /**
     * Defends against a null counter. The column is {@code NOT NULL DEFAULT 0} from V15, so this
     * should be unreachable - but issuing a token with no {@code tv} claim would produce a token the
     * validator refuses, which is a confusing failure. Treating null as 0 keeps issue and validate
     * consistent.
     */
    private static int tokenVersionOf(User user) {
        return user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    public record IssuedToken(String token, long expiresIn) {
    }
}
