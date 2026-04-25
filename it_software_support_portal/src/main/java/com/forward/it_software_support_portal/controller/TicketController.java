package com.forward.it_software_support_portal.controller;

import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    public TicketResponse createTicket(@Valid @RequestBody CreateTicketRequest request) {
        return ticketService.createTicket(request);
    }

    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable Long id) {
        return ticketService.getTicketById(id);
    }

    @GetMapping
    public List<TicketResponse> getAll() {
        return ticketService.getAllTickets();
    }

    @PutMapping("/{ticketId}/assign/{userId}")
    public TicketResponse assignTicket(
            @PathVariable Long ticketId,
            @PathVariable Long userId
    ) {
        return ticketService.assignTicket(ticketId, userId);
    }

    @PatchMapping("/{ticketId}/status")
    public TicketResponse updateStatus(
            @PathVariable Long ticketId,
            @RequestParam TicketStatus status
    ) {
        return ticketService.updateStatus(ticketId, status);
    }
}
