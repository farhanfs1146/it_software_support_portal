package com.forward.it_software_support_portal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * The result of a successful {@code POST /api/auth/login} or {@code POST /api/auth/refresh}.
 *
 * <p>One response type for both, because a refresh produces exactly what a login does: a fresh access
 * token and a fresh refresh token. A client can therefore treat the two endpoints identically and
 * replace its stored pair wholesale, which is the behaviour rotation requires - keeping the old refresh
 * token would mean presenting a spent one on the next refresh and having the session revoked as a
 * suspected replay.
 *
 * <p>The existing fields keep their names and meanings; {@code refreshToken} and
 * {@code refreshExpiresIn} are added, so a Phase 4 or 5 client that ignores them still works.
 */
@Data
@Builder
public class LoginResponse {

    @Schema(description = "Signed access token; send as 'Authorization: Bearer <token>'")
    private String accessToken;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Seconds until the access token expires", example = "1800")
    private long expiresIn;

    /**
     * Opaque, single-use, and the only copy - the server stores a hash. It must be replaced with the
     * one returned by each refresh.
     */
    @Schema(description = "Opaque refresh token. Exchange it at POST /api/auth/refresh for a new pair. "
            + "Single-use: each refresh returns a replacement, and presenting a spent token revokes "
            + "the whole session as a suspected replay. Store it where a page script cannot read it.")
    private String refreshToken;

    @Schema(description = "Seconds until the refresh token expires", example = "604800")
    private long refreshExpiresIn;

    @Schema(description = "The authenticated user's id")
    private Long userId;

    @Schema(description = "The authenticated user's full name")
    private String fullName;

    @Schema(description = "The authenticated user's role")
    private String role;
}
