package com.forward.it_software_support_portal.common.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;

import java.util.Set;

/**
 * Ticket-specific pagination rules.
 *
 * <p>The mechanics live in {@link PageRequests}; this holds only what is specific to tickets - which
 * properties may be sorted on, and the default order. Phase 6 extracted the shared part when the user and
 * application endpoints needed the same behaviour; the public API here is unchanged, so neither
 * {@code TicketController} nor any ticket test needed editing.
 */
public final class TicketPageRequests {

    public static final int DEFAULT_PAGE_SIZE = PageRequests.DEFAULT_PAGE_SIZE;
    public static final int MAX_PAGE_SIZE = PageRequests.MAX_PAGE_SIZE;

    /** Backed by indexes from V12 where it matters; see docs/PERFORMANCE.md. */
    private static final Set<String> SORTABLE = Set.of(
            "createdAt", "updatedAt", "priority", "status", "ticketNumber", "id");

    /** Newest first: what a support queue wants, and what idx_tickets_created_at_id serves. */
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private TicketPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }

    public static HttpHeaders headers(Page<?> page) {
        return PageRequests.headers(page);
    }
}
