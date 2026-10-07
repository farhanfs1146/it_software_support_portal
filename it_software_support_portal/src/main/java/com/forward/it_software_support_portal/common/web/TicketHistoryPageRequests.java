package com.forward.it_software_support_portal.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Page, size and sort for a ticket's audit trail.
 *
 * <p>The sortable set is deliberately two entries. An audit trail is read chronologically; sorting it
 * by {@code oldValue} or {@code actionType} answers no question anyone has, and every whitelisted
 * property is a property the planner may be asked to sort a growing table by. {@code changedAt DESC,
 * id DESC} is both the default and the ordering V8's index already provides.
 */
public final class TicketHistoryPageRequests {

    private static final Set<String> SORTABLE = Set.of("changedAt", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("changedAt"), Sort.Order.desc("id"));

    private TicketHistoryPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
