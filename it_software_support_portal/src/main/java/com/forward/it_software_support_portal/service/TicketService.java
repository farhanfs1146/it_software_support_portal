package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.request.TicketFilter;
import com.forward.it_software_support_portal.dto.response.TicketHistoryResponse;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.enums.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TicketService {

    TicketResponse createTicket(CreateTicketRequest request);

    TicketResponse assignTicket(Long ticketId, Long userId);

    TicketResponse updateStatus(Long ticketId, TicketStatus status);

    TicketResponse getTicketById(Long id);

    /**
     * Paged, filtered ticket list.
     *
     * <p>Replaces the previous {@code getAllTickets()}, which returned every row with no upper bound.
     * That method was the single biggest scalability defect in the application: at 5,100 tickets one
     * call issued 7,001 SQL statements and produced a 1.75 MB response. There is deliberately no
     * unbounded variant any more - every caller goes through a page.
     */
    Page<TicketResponse> searchTickets(TicketFilter filter, Pageable pageable);

    /**
     * One page of a ticket's audit trail, newest first.
     *
     * <p>The trail has been written since Phase 2 but was unreadable: no endpoint exposed it and the
     * repository's only finder was dead code, so every create, assignment and status change was
     * recorded and then invisible. "Who changed this, when, and from what" is the question an audit
     * trail exists to answer.
     *
     * <p>Visibility is the same boundary as {@link #getTicketById}: a caller who may not see the
     * ticket gets a 404, not an empty page - an empty page would confirm the ticket exists.
     */
    Page<TicketHistoryResponse> getTicketHistory(Long ticketId, Pageable pageable);
}
