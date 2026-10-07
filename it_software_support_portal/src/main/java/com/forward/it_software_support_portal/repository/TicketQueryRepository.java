package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.dto.request.TicketFilter;
import com.forward.it_software_support_portal.repository.projection.TicketRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Hand-built read queries for the ticket list.
 *
 * <p>This exists because of a measured problem with the obvious alternative. A single static JPQL
 * query using the familiar {@code (:param is null or column = :param)} idiom produces one SQL
 * statement for every filter combination, which is convenient - but the resulting SQL is not
 * plan-stable. Measured against 20,000 tickets:
 *
 * <pre>
 *   custom plan  (parameters known)   Index Scan using idx_tickets_created_at_id        145 buffers
 *   generic plan (parameters opaque)  Seq Scan + Hash joins + top-N heapsort         86,026 buffers
 * </pre>
 *
 * <p>PostgreSQL's default {@code plan_cache_mode = auto} keeps choosing the custom plan here, because
 * the generic estimate is much more expensive - so the cliff is not reached today. But that safety
 * rests entirely on cost estimates staying that way as data grows, and the PostgreSQL JDBC driver
 * switches to server-side prepared statements after five executions of a statement, which is what
 * makes the generic plan reachable at all. Depending on an estimate to avoid a 590x regression is not
 * a property worth shipping.
 *
 * <p>Building the predicates dynamically removes the question: each filter combination produces SQL
 * that mentions only the filters actually supplied, so there is no opaque parameter for the planner to
 * guess around and every plan is the custom plan.
 */
public interface TicketQueryRepository {

    /**
     * One page of tickets, projected to exactly the columns the response needs.
     *
     * @param filter   optional filters; any {@code null} field is omitted from the SQL entirely
     * @param pageable page, size and sort; sort properties are validated before reaching here
     */
    Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable);

    /**
     * As above, but restricted to tickets the given user raised or is assigned to.
     *
     * <p>This is an authorization boundary applied by the service for callers without
     * {@code TICKET_READ_ALL}. It is ANDed with the caller's own filters, so it can only narrow a
     * result set, never widen one.
     */
    Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable, Long restrictToUserId);
}
