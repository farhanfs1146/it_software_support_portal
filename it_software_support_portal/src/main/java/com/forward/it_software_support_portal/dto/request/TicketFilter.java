package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.Priority;
import com.forward.it_software_support_portal.enums.TicketStatus;

/**
 * Optional filters for the ticket list. Every field may be {@code null}, meaning "do not filter".
 *
 * <p>The set is deliberately small, and each entry is backed by measured evidence rather than by
 * guessing at what might be useful:
 *
 * <ul>
 *   <li>{@code status} - the core support-queue question ("what is still open"); its filtered
 *       {@code count} becomes an index-only scan via {@code idx_tickets_status_created_at}.
 *   <li>{@code priority} - triage. Served by the {@code created_at} index at these volumes; a
 *       dedicated priority index was built, measured, used zero times, and discarded.
 *   <li>{@code assignedToUserId} - an agent's own queue; uses
 *       {@code idx_tickets_assigned_to_created_at}.
 *   <li>{@code raisedByUserId} - "tickets I reported"; uses {@code idx_tickets_raised_by_created_at}.
 * </ul>
 *
 * <p>No date-range, application or free-text filter is offered, because nothing in the application
 * requires one yet and an unused filter would only invite an unused index. See docs/PERFORMANCE.md.
 */
public record TicketFilter(
        TicketStatus status,
        Priority priority,
        Long assignedToUserId,
        Long raisedByUserId
) {

    public static TicketFilter none() {
        return new TicketFilter(null, null, null, null);
    }
}
