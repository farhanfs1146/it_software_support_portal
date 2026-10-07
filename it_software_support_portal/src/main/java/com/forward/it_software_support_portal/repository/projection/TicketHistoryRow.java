package com.forward.it_software_support_portal.repository.projection;

import java.time.LocalDateTime;

/**
 * One audit entry, read as a projection.
 *
 * <p>A projection rather than the entity for the usual reason in this codebase, and here it matters
 * more than most: {@code TicketHistoryTracking} has two {@code @ManyToOne} associations
 * ({@code ticket} and {@code changedBy}) which default to EAGER, so reading a page of entities would
 * issue a select per distinct referenced ticket and user - audit finding P1-1 reproduced on a new
 * endpoint. Selecting the actor's name directly makes a page exactly two statements.
 *
 * <p>{@code changedByFullName} mirrors how {@code TicketResponse} reports people: by name, not id.
 */
public record TicketHistoryRow(
        Long id,
        String actionType,
        String fieldName,
        String oldValue,
        String newValue,
        String changedByFullName,
        String remarks,
        LocalDateTime changedAt
) {
}
