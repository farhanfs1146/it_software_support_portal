package com.forward.it_software_support_portal.repository.projection;

import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import com.forward.it_software_support_portal.enums.TicketStatus;

import java.time.LocalDateTime;

/**
 * Flat read projection for ticket queries - exactly the columns {@code TicketResponse} needs and
 * nothing else.
 *
 * <p><strong>Why a projection rather than entities.</strong> The list endpoint used to call
 * {@code findAll()}, which loads {@code Ticket} entities whose three {@code @ManyToOne} associations
 * are {@code EAGER}. Spring Data's {@code findAll} issues {@code select t from Ticket t}, and
 * Hibernate resolves eager to-one associations in HQL results with follow-up selects rather than a
 * join - producing one extra query per distinct referenced user and application. Measured worst case:
 * 200 tickets to 401 statements.
 *
 * <p>Selecting into this record makes Hibernate emit a single query with explicit joins, and loads
 * <em>no managed entities at all</em>: nothing enters the persistence context, nothing is
 * dirty-checked, and only the three name columns actually rendered in the response are read from the
 * associated tables.
 *
 * <p>It is a separate type from {@code TicketResponse} on purpose. Binding JPQL constructor
 * expressions straight to the response DTO would couple the query to that DTO's field order, so
 * reordering a field in the API model would silently shuffle values between columns. Mapping through
 * this record costs one allocation per row and makes such a mistake a compile error instead.
 */
public record TicketRow(
        Long id,
        String ticketNumber,
        String title,
        String description,
        IssueType issueType,
        Priority priority,
        TicketStatus status,
        String businessImpact,
        LocalDateTime expectedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime resolvedAt,
        String raisedByFullName,
        String assignedToFullName,
        String applicationName,

        /**
         * Ownership ids, carried for authorization rather than for the response.
         *
         * <p>Resource-level checks need to know who raised a ticket and who it is assigned to. Reading
         * these from the same projection keeps the Phase 3 guarantee intact - the authorization check
         * costs no additional query, because the ids travel with the row that was being fetched anyway.
         * Neither field is mapped into {@code TicketResponse}, so neither changes the API contract.
         */
        Long raisedByUserId,
        Long assignedToUserId
) {

    /** Whether the given user raised this ticket or is its assignee. */
    public boolean involves(Long userId) {
        if (userId == null) {
            return false;
        }
        return userId.equals(raisedByUserId) || userId.equals(assignedToUserId);
    }
}
