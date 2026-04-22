package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import lombok.Data;

@Data
public class CreateTicketRequest {

    private String title;
    private String description;
    private String applicationName;
    private IssueType issueType;
    private Priority priority;
}
