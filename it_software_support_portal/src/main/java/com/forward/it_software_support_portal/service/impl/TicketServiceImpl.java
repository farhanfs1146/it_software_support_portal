package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.entity.TicketHistoryTracking;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.repository.TicketHistoryTrackingRepository;
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
    private final TicketHistoryTrackingRepository ticketHistoryTrackingRepository;

    // Overall Flow:
    // OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED

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

        saveHistory(
                saved.getId(),
                "CREATED",
                "status",
                null,
                TicketStatus.OPEN.name(),
                raisedBy.getId(),
                "Ticket created successfully"
        );

        return mapToResponse(saved);
    }

    @Override
    public TicketResponse assignTicket(Long ticketId, Long userId) {

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        User newAssignedUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("Assigned user not found"));

        String oldAssignedUser = ticket.getAssignedTo() != null
                ? ticket.getAssignedTo().getFullName()
                : null;

        String oldStatus = ticket.getStatus().name();

        ticket.setAssignedTo(newAssignedUser);
        ticket.setStatus(TicketStatus.ASSIGNED);
        ticket.setUpdatedAt(LocalDateTime.now());

        Ticket updatedTicket = ticketRepository.save(ticket);

        saveHistory(
                ticketId,
                "ASSIGNED",
                "assigned_to",
                oldAssignedUser,
                newAssignedUser.getFullName(),
                newAssignedUser.getId(),
                "Ticket assigned to user"
        );

        saveHistory(
                ticketId,
                "STATUS_CHANGED",
                "status",
                oldStatus,
                TicketStatus.ASSIGNED.name(),
                newAssignedUser.getId(),
                "Status changed automatically on assignment"
        );

        return mapToResponse(updatedTicket);
    }

    @Override
    public TicketResponse updateStatus(Long ticketId, TicketStatus status) {

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        String oldStatus = ticket.getStatus().name();

        ticket.setStatus(status);
        ticket.setUpdatedAt(LocalDateTime.now());

        if (status == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(LocalDateTime.now());
        }

        Ticket updatedTicket = ticketRepository.save(ticket);

        Long changedByUserId = ticket.getAssignedTo().getId();

        saveHistory(
                ticketId,
                "STATUS_CHANGED",
                "status",
                oldStatus,
                status.name(),
                changedByUserId,
                "Ticket status updated"
        );

        return mapToResponse(updatedTicket);
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

    private void saveHistory(
            Long ticketId,
            String actionType,
            String fieldName,
            String oldValue,
            String newValue,
            Long changedBy,
            String remarks
    ) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new RuntimeException("Ticket not found"));

        User user = userRepository.findById(changedBy)
                .orElseThrow(() -> new RuntimeException("User not found"));

        TicketHistoryTracking history = new TicketHistoryTracking();
        history.setTicket(ticket);
        history.setActionType(actionType);
        history.setFieldName(fieldName);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setChangedBy(user);
        history.setRemarks(remarks);
        history.setChangedAt(LocalDateTime.now());

        ticketHistoryTrackingRepository.save(history);
    }
}
