package com.forward.it_software_support_portal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * A successful authentication result.
 *
 * <p>Carries no password, no hash and no security metadata beyond what a client needs in order to use
 * and renew the token. {@code role} is included because the SPA needs it to decide what to render - it
 * is not what the server trusts; authority always comes from the token the server verifies.
 */
@Data
@Builder
public class LoginResponse {

    @Schema(description = "Signed access token; send as 'Authorization: Bearer <token>'")
    private String accessToken;

    @Schema(description = "Token type", example = "Bearer")
    private String tokenType;

    @Schema(description = "Seconds until the token expires", example = "1800")
    private long expiresIn;

    @Schema(description = "The authenticated user's id")
    private Long userId;

    @Schema(description = "The authenticated user's full name")
    private String fullName;

    @Schema(description = "The authenticated user's role")
    private String role;
}
