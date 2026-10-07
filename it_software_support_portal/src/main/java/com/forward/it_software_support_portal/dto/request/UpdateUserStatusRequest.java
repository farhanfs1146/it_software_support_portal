package com.forward.it_software_support_portal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Activates or deactivates an account. Deactivating also ends every session it currently holds. */
@Data
public class UpdateUserStatusRequest {

    @NotNull(message = "active is required")
    @Schema(description = "true to activate the account, false to deactivate it", example = "false")
    private Boolean active;
}
