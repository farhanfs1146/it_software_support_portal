package com.forward.it_software_support_portal.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TicketResponse {

    private Long id;
    private String ticketNumber;
    private String title;
    private String description;
    private String issueType;
    private String priority;
    private String status;
    private String applicationName;
    private String raisedBy;
    private String assignedTo;
    private String businessImpact;
    private LocalDateTime createdAt;
}