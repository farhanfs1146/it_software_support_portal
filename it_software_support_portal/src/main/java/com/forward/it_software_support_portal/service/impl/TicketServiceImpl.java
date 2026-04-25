package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.repository.TicketRepository;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.util.List;


@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;

    @Override
    public TicketResponse createTicket(CreateTicketRequest request) {

        User raisedBy = userRepository.findById(request.getRaisedByUserId())
                .orElseThrow(() -> new RuntimeException("Raised by user not found"));

        User assignedTo = userRepository.findById(request.getAssignedToUserId())
                .orElseThrow(() -> new RuntimeException("Assigned user not found"));

        Application application = applicationRepository.findById(request.getApplicationId())
                .orElseThrow(() -> new RuntimeException("Application not found"));

        Ticket ticket = new Ticket();
        ticket.setTicketNumber("TKT-" + System.currentTimeMillis());
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setIssueType(request.getIssueType());
        ticket.setPriority(request.getPriority());
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setBusinessImpact(request.getBusinessImpact());
        ticket.setExpectedBy(request.getExpectedBy());
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticket.setRaisedBy(raisedBy);
        ticket.setAssignedTo(assignedTo);
        ticket.setApplication(application);

        Ticket saved = ticketRepository.save(ticket);

        return mapToResponse(saved);
    }

    @Override
    public TicketResponse assignTicket(Long ticketId, Long userId) {
        return null;
    }

    @Override
    public TicketResponse updateStatus(Long ticketId, TicketStatus status) {
        return null;
    }

    @Override
    public TicketResponse getTicketById(Long id) {
        return mapToResponse(
                ticketRepository.findById(id)
                        .orElseThrow(() -> new RuntimeException("Ticket not found"))
        );
    }

    @Override
    public List<TicketResponse> getAllTickets() {
        return ticketRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    private TicketResponse mapToResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .ticketNumber(ticket.getTicketNumber())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .issueType(ticket.getIssueType().name())
                .priority(ticket.getPriority().name())
                .status(ticket.getStatus().name())
                .businessImpact(ticket.getBusinessImpact())
                .expectedBy(ticket.getExpectedBy())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .resolvedAt(ticket.getResolvedAt())
                .raisedBy(ticket.getRaisedBy().getFullName())
                .assignedTo(ticket.getAssignedTo().getFullName())
                .applicationName(ticket.getApplication().getAppName())
                .build();
    }
}
