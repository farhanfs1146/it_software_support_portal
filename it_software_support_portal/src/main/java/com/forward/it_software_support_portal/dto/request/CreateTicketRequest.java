package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

@Data
public class CreateTicketRequest {

    @NotBlank(message = "Shorter & Descriptive title")
    @Schema(description = "Shorter & Descriptive title of which problem you're facing")
    private String title;

    @NotBlank(message = "Description of which problem you're facing")
    @Schema(description = "Descriptive of which problem you're facing in explained-form")
    private String description;

    @NotNull(message = "Select the issue type of ticket.")
    @Schema(description = "Select in the drop-down whether issue is new, bug, improvement etc.")
    private IssueType issueType;

    @NotNull(message = "select the priority of ticket")
    @Schema(description = "Select in the drop-down whether ticket priority is low, high or critical very urgent etc.")
    private Priority priority;

    @Schema(description = "select the impact of this ticket whether it is department-level, user-level")
    private String businessImpact;

    @Schema(description = "select the date of when this ticket should resolve.")
    private LocalDateTime expectedBy;

    @NotNull(message = "Select the application for which you're creating ticket")
    @Schema(description = "Applicant have to mention clearly for which application they're creating/rasing ticket")
    private Long applicationId;

    @NotBlank(message = "select the module of selected application.")
    @Schema(description = "Which module of selected application facing issue.")
    private String moduleName;
}
