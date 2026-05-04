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

    // we will get the user id or login user id dynamically, this is best approach rather than to ask user to set id etc.
    // we will set this id by log-in users not asked users.
//    @NotNull
//    private Long raisedByUserId;

    // This will be null for now, because while raising ticket by any-user doesn't fit the approach to assign to developer,
    // After the HOD/Manager of IT department will allow to assign the required task to the developer.
    // By selecting the drop-down of active developers.
    // we will set this later by actions of authorized persons not users while creating ticket.
//    @NotNull
//    private Long assignedToUserId;

    // Applicant have to mention clearly which application having issue.
    @NotNull
    private Long applicationId;

    // Missing important thing is which module of selected application facing issue.
    @NotNull
    private String moduleName;
}
