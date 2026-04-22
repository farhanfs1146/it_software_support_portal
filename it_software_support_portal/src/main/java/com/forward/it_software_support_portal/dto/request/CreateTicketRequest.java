package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Data
public class CreateTicketRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Description is required")
    private String description;

    @NotNull(message = "Issue type is required")
    private IssueType issueType;

    @NotNull(message = "Priority is required")
    private Priority priority;

    @NotNull(message = "Raised by user id is required")
    private Long raisedByUserId;

    private Long assignedToUserId;

    @NotNull(message = "Application id is required")
    private Long applicationId;

    private String businessImpact;
}
