package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

@Data
public class CreateTicketRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotNull
    private IssueType issueType;

    @NotNull
    private Priority priority;

    private String businessImpact;

    private LocalDateTime expectedBy;

    @NotNull
    private Long raisedByUserId;

    @NotNull
    private Long assignedToUserId;

    @NotNull
    private Long applicationId;
}
