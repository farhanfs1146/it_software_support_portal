package com.forward.it_software_support_portal.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TicketResponse {

    private Long id;
    private String ticketNumber;
    private String title;
    private String status;
    private String assignedTo;
}