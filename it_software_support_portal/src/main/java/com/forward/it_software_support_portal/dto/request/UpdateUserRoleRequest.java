package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Changes a user's role, and therefore their permissions.
 *
 * <p>Changing the role ends every session the user holds. Authorities are carried in the access token's
 * {@code role} claim, so without that a demoted user would keep their old permissions until the token
 * expired - which is the wrong direction for a demotion, the case that actually matters.
 */
@Data
public class UpdateUserRoleRequest {

    @NotNull(message = "role is required")
    @Schema(description = "The role to assign", example = "IT_SUPPORT")
    private Role role;
}
