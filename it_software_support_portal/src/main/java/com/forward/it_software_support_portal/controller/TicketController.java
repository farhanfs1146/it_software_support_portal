package com.forward.it_software_support_portal.controller;

import com.forward.it_software_support_portal.common.web.PageRequests;
import com.forward.it_software_support_portal.common.web.TicketHistoryPageRequests;
import com.forward.it_software_support_portal.common.web.TicketPageRequests;
import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.request.TicketFilter;
import com.forward.it_software_support_portal.dto.response.TicketHistoryResponse;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.enums.Priority;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.service.TicketService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PreAuthorize("hasAuthority('TICKET_CREATE')")
    @PostMapping
    public TicketResponse createTicket(@Valid @RequestBody CreateTicketRequest request) {
        return ticketService.createTicket(request);
    }

    /**
     * Readable by anyone involved in the ticket, or by a caller holding TICKET_READ_ALL.
     * The involvement check is resource-level and lives in the service, which can see the ticket.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping("/{id}")
    public TicketResponse getById(@PathVariable Long id) {
        return ticketService.getTicketById(id);
    }

    /**
     * Paged, filtered, sorted ticket list.
     *
     * <p><strong>Response body shape is unchanged:</strong> still a JSON array of
     * {@code TicketResponse}. What changed is that it is now a bounded page rather than the entire
     * table, and that pagination metadata travels in headers ({@code X-Total-Count},
     * {@code X-Total-Pages}, {@code X-Page-Number}, {@code X-Page-Size}, {@code X-Has-Next}).
     *
     * <p>Headers were chosen over a wrapper object specifically to keep the body parseable by existing
     * clients. The behavioural change is unavoidable - an unbounded list was the defect - but a client
     * that ignores the new parameters now receives the newest
     * {@value TicketPageRequests#DEFAULT_PAGE_SIZE} tickets instead of every row. See
     * docs/PERFORMANCE.md.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping
    public ResponseEntity<List<TicketResponse>> getAll(
            @Parameter(description = "Filter by ticket status")
            @RequestParam(required = false) TicketStatus status,
            @Parameter(description = "Filter by priority")
            @RequestParam(required = false) Priority priority,
            @Parameter(description = "Filter by the assigned user's id")
            @RequestParam(required = false) Long assignedTo,
            @Parameter(description = "Filter by the raising user's id")
            @RequestParam(required = false) Long raisedBy,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. "
                    + "Allowed: createdAt, updatedAt, priority, status, ticketNumber, id. "
                    + "'priority' orders by severity (LOW < MEDIUM < HIGH < CRITICAL), so "
                    + "'priority,desc' is worst-first. 'status' groups equal statuses together but "
                    + "is not lifecycle order - no lifecycle is defined yet.")
            @RequestParam(required = false) String sort
    ) {
        Pageable pageable = TicketPageRequests.of(page, size, sort);
        TicketFilter filter = new TicketFilter(status, priority, assignedTo, raisedBy);

        Page<TicketResponse> result = ticketService.searchTickets(filter, pageable);

        return ResponseEntity.ok()
                .headers(TicketPageRequests.headers(result))
                .body(result.getContent());
    }

    /**
     * One page of a ticket's audit trail, newest first.
     *
     * <p>Guarded by {@code TICKET_READ_OWN} exactly like {@code GET /api/tickets/{id}}, and the
     * service applies the same involvement check to the ticket itself - so a caller who cannot read a
     * ticket cannot read its history either, and gets the same 404 rather than an empty page.
     *
     * <p>Same shape as every other listing here: a JSON array body with pagination metadata in
     * {@code X-Total-Count}, {@code X-Total-Pages}, {@code X-Page-Number}, {@code X-Page-Size} and
     * {@code X-Has-Next}.
     */
    @PreAuthorize("hasAuthority('TICKET_READ_OWN')")
    @GetMapping("/{id}/history")
    public ResponseEntity<List<TicketHistoryResponse>> getHistory(
            @PathVariable Long id,
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. Allowed: changedAt, id")
            @RequestParam(required = false) String sort
    ) {
        Page<TicketHistoryResponse> result =
                ticketService.getTicketHistory(id, TicketHistoryPageRequests.of(page, size, sort));

        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    /**
     * {@code userId} is the assignment TARGET, never the actor. The acting user comes from the
     * authenticated principal; a client cannot nominate who performed the assignment.
     */
    @PreAuthorize("hasAuthority('TICKET_ASSIGN')")
    @PutMapping("/{ticketId}/assign/{userId}")
    public TicketResponse assignTicket(
            @PathVariable Long ticketId,
            @PathVariable Long userId
    ) {
        return ticketService.assignTicket(ticketId, userId);
    }

    /**
     * Requires the status-change permission outright.
     *
     * <p>Whether a requester should be able to move their own ticket - closing it once satisfied, for
     * instance - is a business rule this codebase does not define, so the safe direction is to withhold
     * it. See docs/SECURITY.md, "Pending business decisions".
     */
    @PreAuthorize("hasAuthority('TICKET_STATUS_CHANGE')")
    @PatchMapping("/{ticketId}/status")
    public TicketResponse updateStatus(
            @PathVariable Long ticketId,
            @RequestParam TicketStatus status
    ) {
        return ticketService.updateStatus(ticketId, status);
    }
}
