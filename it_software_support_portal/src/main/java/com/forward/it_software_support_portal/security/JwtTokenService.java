package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.entity.User;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Issues signed access tokens.
 *
 * <p>Delegates all signing to Spring Security's {@link JwtEncoder} (Nimbus underneath). No JWT is
 * assembled, signed or parsed by hand anywhere in this codebase - that is the part of token handling
 * where a mistake is both easy and fatal.
 *
 * <p><strong>Claims, and what is deliberately absent.</strong> The token carries {@code sub} (the
 * application user id), {@code iss}, {@code iat}, {@code exp}, plus {@code email} and {@code role} for
 * convenience. It does <em>not</em> carry granted authorities: those are derived from the role on every
 * request by {@link JwtRoleAuthoritiesConverter}. That way a change to {@link RolePermissions} takes
 * effect immediately instead of only after every outstanding token expires.
 */
@Service
public class JwtTokenService {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;

    public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /** @return a signed access token identifying the given user */
    public IssuedToken issue(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .build();

        String token = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(SecurityConfig.JWS_ALGORITHM).build(), claims))
                .getTokenValue();

        return new IssuedToken(token, properties.jwt().ttl().toSeconds());
    }

    /**
     * @param token     the signed JWT
     * @param expiresIn lifetime in seconds, so a client knows when to re-authenticate
     */
    public record IssuedToken(String token, long expiresIn) {
    }
}
