package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.entity.TicketHistoryTracking;
import com.forward.it_software_support_portal.repository.projection.TicketHistoryRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketHistoryTrackingRepository extends JpaRepository<TicketHistoryTracking, Long> {

    /**
     * One page of a ticket's audit trail, newest first.
     *
     * <p>Replaces {@code findByTicketIdOrderByChangedAtDesc}, which returned an unbounded
     * {@code List} and was called from nowhere. Paging it follows the rule Phase 6 applied to every
     * other listing - no unbounded variant remains - and matters here because the trail is the one
     * collection in this schema that only ever grows: it gains a row per change and nothing deletes
     * them.
     *
     * <p>{@code h.ticket.id} is the foreign-key column, so it needs no join, and the default ordering
     * is exactly the index V8 created,
     * {@code idx_ticket_history_tracking_ticket_changed_at (ticket_id, changed_at DESC)}. The join to
     * {@code changedBy} is inner because {@code changed_by} is {@code NOT NULL} with a foreign key
     * (V11).
     */
    @Query(value = """
            select new com.forward.it_software_support_portal.repository.projection.TicketHistoryRow(
                h.id, h.actionType, h.fieldName, h.oldValue, h.newValue,
                actor.fullName, h.remarks, h.changedAt)
            from TicketHistoryTracking h
                join h.changedBy actor
            where h.ticket.id = :ticketId
            """,
            countQuery = """
                    select count(h.id) from TicketHistoryTracking h where h.ticket.id = :ticketId
                    """)
    Page<TicketHistoryRow> findHistoryRows(@Param("ticketId") Long ticketId, Pageable pageable);
}
