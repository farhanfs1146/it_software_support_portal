package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.enums.TicketStatus;

import java.util.List;

public interface TicketService {

    TicketResponse createTicket(CreateTicketRequest request);

    TicketResponse assignTicket(Long ticketId, Long userId);

    TicketResponse updateStatus(Long ticketId, TicketStatus status);

    TicketResponse getTicketById(Long id);

    List<TicketResponse> getAllTickets();
}
