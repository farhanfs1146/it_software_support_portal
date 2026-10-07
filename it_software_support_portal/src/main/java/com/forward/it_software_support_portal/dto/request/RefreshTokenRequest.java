package com.forward.it_software_support_portal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Carries a refresh token for {@code POST /api/auth/refresh} and {@code POST /api/auth/logout}.
 *
 * <p>In the body rather than a query parameter, deliberately. A query string ends up in access logs,
 * in browser history and in the {@code Referer} header of the next request - all places a long-lived
 * credential must not be. (The {@code Referrer-Policy: no-referrer} header added in Phase 5 covers the
 * last of those, but relying on one header to keep a credential out of three sinks is not a design.)
 *
 * <p>The size bound is generous rather than exact: the generator emits 43 base64url characters, and
 * pinning the field to that length would turn a future change of token length into a validation
 * failure. 200 is small enough that nothing large reaches the hashing code.
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "A refresh token is required")
    @Size(max = 200, message = "Refresh token must be at most 200 characters")
    @Schema(description = "The refresh token issued by login or by a previous refresh",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String refreshToken;
}
