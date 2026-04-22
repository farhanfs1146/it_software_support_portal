package com.forward.it_software_support_portal.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignTicketRequest {

    @NotNull(message = "Assigned user id is required")
    private Long assignedToUserId;
}
