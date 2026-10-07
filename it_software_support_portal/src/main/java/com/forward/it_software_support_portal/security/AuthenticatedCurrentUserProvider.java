package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.common.identity.MissingUserIdentityException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the acting user from the authenticated principal.
 *
 * <p>This replaces {@code RequestHeaderCurrentUserProvider}, which read an {@code X-User-Id} header.
 * That mechanism was a deliberate, fail-closed stopgap introduced in Phase 2 to stop the application
 * silently attributing everything to user id 1 - but it trusted a value the client chose, so any caller
 * could act as any user. It is now <strong>deleted</strong>, not merely bypassed: the header is no
 * longer read anywhere, so it cannot override or influence identity.
 *
 * <p><strong>No database lookup happens here.</strong> The user id comes from the token's {@code sub}
 * claim, which the resource server has already verified by signature, expiry and issuer. The services
 * that genuinely need a {@code User} entity - because {@code raised_by} and {@code changed_by} are
 * foreign keys - load it themselves at that point, so the lookup happens exactly where it is required
 * and nowhere else. No cache was added; there is nothing yet to cache.
 *
 * <p><strong>It fails closed.</strong> No authentication, an anonymous authentication, a principal that
 * is not a JWT, or a {@code sub} that is not a positive number all raise
 * {@link MissingUserIdentityException}. There is no default user and no fallback.
 */
@Component
public class AuthenticatedCurrentUserProvider implements CurrentUserProvider {

    @Override
    public Long requireCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new MissingUserIdentityException("No authenticated user is present");
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new MissingUserIdentityException(
                    "The authenticated principal does not carry a verified token");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new MissingUserIdentityException("The access token carries no subject claim");
        }

        long userId;
        try {
            userId = Long.parseLong(subject.trim());
        } catch (NumberFormatException e) {
            throw new MissingUserIdentityException("The access token subject is not a user id");
        }
        if (userId <= 0) {
            throw new MissingUserIdentityException("The access token subject is not a valid user id");
        }
        return userId;
    }

    /**
     * @return true when the current caller holds the given permission
     *
     * <p>Used for resource-level decisions that an endpoint annotation cannot express - for example
     * "may this caller read a ticket they are not involved in". Keeping the check here means service
     * code asks a question about permissions rather than importing Spring Security types.
     */
    public static boolean currentUserHas(Permission permission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> permission.name().equals(granted.getAuthority()));
    }
}
