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
    private String businessImpact;
    private LocalDateTime expectedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime resolvedAt;
    private String raisedBy;
    private String assignedTo;
    private String applicationName;
}