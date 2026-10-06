package com.forward.it_software_support_portal.repository;


import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.repository.projection.TicketRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long>, TicketQueryRepository {

    Optional<Ticket> findByTicketNumber(String ticketNumber);

    /**
     * Draws the next value from {@code ticket_number_seq} (audit finding P0-5).
     *
     * <p>{@code nextval} is atomic and never hands the same value to two callers, whatever the
     * concurrency. It is also intentionally non-transactional: if the surrounding ticket creation
     * rolls back, the number is simply not reused. Gaps are acceptable; duplicates are not.
     */
    @Query(value = "SELECT nextval('ticket_number_seq')", nativeQuery = true)
    long nextTicketNumberValue();

    /**
     * Single-ticket read, projected for the same reasons as the list query.
     *
     * <p>Loading the entity here already cost only one statement, because Hibernate's entity loader
     * joins eager to-one associations. The projection is still preferable: it keeps the response path
     * free of managed entities and dirty checking, and reads only the columns the response renders.
     *
     * <p>This one stays as static JPQL - it has no optional filters, so there is no plan-stability
     * concern of the kind that drove the list query into {@link TicketQueryRepositoryImpl}.
     */
    @Query("""
            select new com.forward.it_software_support_portal.repository.projection.TicketRow(
                t.id, t.ticketNumber, t.title, t.description, t.issueType, t.priority, t.status,
                t.businessImpact, t.expectedBy, t.createdAt, t.updatedAt, t.resolvedAt,
                raiser.fullName, assignee.fullName, app.appName,
                raiser.id, assignee.id)
            from Ticket t
                join t.raisedBy raiser
                left join t.assignedTo assignee
                join t.application app
            where t.id = :id
            """)
    Optional<TicketRow> findTicketRowById(@Param("id") Long id);
}
