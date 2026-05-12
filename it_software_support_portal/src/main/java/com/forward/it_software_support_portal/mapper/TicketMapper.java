package com.forward.it_software_support_portal.mapper;

import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.entity.Ticket;
import org.springframework.stereotype.Component;

@Component
public class TicketMapper {

    public TicketResponse toResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .ticketNumber(ticket.getTicketNumber())
                .title(ticket.getTitle())
                .status(ticket.getStatus().name())
                .build();
    }
}
