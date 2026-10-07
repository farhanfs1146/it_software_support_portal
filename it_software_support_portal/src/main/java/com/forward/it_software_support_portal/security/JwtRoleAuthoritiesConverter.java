package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a validated token into an authentication carrying this application's permissions.
 *
 * <p>Authorities are computed from the token's {@code role} claim through {@link RolePermissions} on
 * every request, rather than read from the token. The difference matters: if an administrator changes
 * what a role may do, the new rules apply to the next request instead of waiting for every issued
 * token to expire.
 *
 * <p>Both a permission authority per {@link Permission} and a conventional {@code ROLE_<NAME>}
 * authority are granted. Checks throughout the application use permissions; the role authority exists
 * only so that role-shaped expressions remain possible without re-deriving the mapping.
 *
 * <p>An unrecognised role grants nothing. A token whose role claim no longer matches the enum - say
 * after a role is removed - therefore authenticates but authorises nothing, which is the safe
 * direction to fail in.
 */
@Component
public class JwtRoleAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Logger log = LoggerFactory.getLogger(JwtRoleAuthoritiesConverter.class);

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    private List<GrantedAuthority> authorities(Jwt jwt) {
        String roleClaim = jwt.getClaimAsString(JwtClaims.ROLE);
        if (roleClaim == null || roleClaim.isBlank()) {
            log.warn("Token for subject {} carries no role claim; granting no authorities", jwt.getSubject());
            return List.of();
        }

        Role role;
        try {
            role = Role.valueOf(roleClaim);
        } catch (IllegalArgumentException e) {
            log.warn("Token for subject {} carries unknown role '{}'; granting no authorities",
                    jwt.getSubject(), roleClaim);
            return List.of();
        }

        Set<Permission> permissions = RolePermissions.of(role);
        List<GrantedAuthority> authorities = new ArrayList<>(permissions.size() + 1);
        for (Permission permission : permissions) {
            authorities.add(new SimpleGrantedAuthority(permission.name()));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        return authorities;
    }
}
