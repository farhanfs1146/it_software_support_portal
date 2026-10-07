package com.forward.it_software_support_portal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * A user changing their own password.
 *
 * <p>{@code currentPassword} is required and verified, so a stolen access token alone is not enough
 * to take an account over permanently - the attacker would still have to know the password to replace
 * it.
 *
 * <p>The length rule on {@code newPassword} is deliberately the same as
 * {@code CreateUserRequest.password}: a password set through this endpoint must meet the same bar as
 * one an administrator sets, or the weaker path becomes the one everybody uses.
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    @Size(max = 200, message = "Current password must be at most 200 characters")
    @Schema(description = "The password being replaced", accessMode = Schema.AccessMode.WRITE_ONLY)
    private String currentPassword;

    @NotBlank(message = "A new password is required")
    @Size(min = 12, max = 200, message = "Password must be between 12 and 200 characters")
    @Schema(description = "The replacement password. Stored only as a BCrypt hash and never returned.",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String newPassword;
}
