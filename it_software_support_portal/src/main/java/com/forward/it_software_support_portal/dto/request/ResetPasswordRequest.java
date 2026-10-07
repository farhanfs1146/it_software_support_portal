package com.forward.it_software_support_portal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * An administrator setting a password for someone else.
 *
 * <p>There is no {@code currentPassword} field, and that is the whole difference from
 * {@link ChangePasswordRequest}: an administrator resetting an account by definition does not know the
 * password being replaced. The authority to do it comes from {@code USER_MANAGE} instead, and the reset
 * ends every session that account had.
 *
 * <p>The 12-character minimum matches {@code ChangePasswordRequest}, so a reset cannot be used as a way
 * around the policy a user's own change has to satisfy.
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "A new password is required")
    @Size(min = 12, max = 200, message = "Password must be between 12 and 200 characters")
    @Schema(description = "The replacement password. Stored only as a BCrypt hash and never returned.",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String newPassword;
}
