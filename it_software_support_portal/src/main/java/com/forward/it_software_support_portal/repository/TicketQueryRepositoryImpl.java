package com.forward.it_software_support_portal.repository;

import com.forward.it_software_support_portal.dto.request.TicketFilter;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Priority;
import com.forward.it_software_support_portal.repository.projection.TicketRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria-API implementation of {@link TicketQueryRepository}.
 *
 * <p>Two queries per page and no more: one for the rows, one for the total. Both are built with only
 * the predicates the caller actually supplied, which is what keeps the plans stable - see the
 * interface for the measurements that motivated this.
 */
public class TicketQueryRepositoryImpl implements TicketQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable) {
        return findTicketRows(filter, pageable, null);
    }

    /**
     * @param restrictToUserId when non-null, results are restricted to tickets this user raised or is
     *                         assigned to. This is the <strong>authorization</strong> boundary for the
     *                         collection endpoint, not a user-supplied filter: a caller without
     *                         {@code TICKET_READ_ALL} cannot widen it, and because it is ANDed with the
     *                         request's own filters, asking for someone else's tickets simply returns
     *                         nothing rather than leaking a count.
     */
    @Override
    public Page<TicketRow> findTicketRows(TicketFilter filter, Pageable pageable, Long restrictToUserId) {
        TicketFilter effective = filter == null ? TicketFilter.none() : filter;

        List<TicketRow> rows = fetchRows(effective, pageable, restrictToUserId);
        // PageableExecutionUtils skips the count query when the total is already implied - a first
        // page that came back short cannot have more rows behind it. That removes the second query
        // entirely for small result sets, which is the common case for a filtered queue view.
        return PageableExecutionUtils.getPage(rows, pageable, () -> countRows(effective, restrictToUserId));
    }

    private List<TicketRow> fetchRows(TicketFilter filter, Pageable pageable, Long restrictToUserId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<TicketRow> query = cb.createQuery(TicketRow.class);
        Root<Ticket> ticket = query.from(Ticket.class);

        // raisedBy and application are NOT NULL, so inner joins cannot drop rows.
        // assignedTo is nullable and an unassigned ticket must still appear, hence the LEFT join.
        Join<Ticket, User> raiser = ticket.join("raisedBy");
        Join<Ticket, User> assignee = ticket.join("assignedTo", JoinType.LEFT);
        Join<Ticket, Application> application = ticket.join("application");

        query.select(cb.construct(TicketRow.class,
                ticket.get("id"),
                ticket.get("ticketNumber"),
                ticket.get("title"),
                ticket.get("description"),
                ticket.get("issueType"),
                ticket.get("priority"),
                ticket.get("status"),
                ticket.get("businessImpact"),
                ticket.get("expectedBy"),
                ticket.get("createdAt"),
                ticket.get("updatedAt"),
                ticket.get("resolvedAt"),
                raiser.get("fullName"),
                assignee.get("fullName"),
                application.get("appName"),
                raiser.get("id"),
                assignee.get("id")));

        List<Predicate> predicates = predicates(cb, ticket, assignee, raiser, filter);
        if (restrictToUserId != null) {
            // Deliberately compares the TICKET's own foreign-key columns rather than the joined
            // users' primary keys. Writing this as (raiser.id = ? or assignee.id = ?) is logically
            // identical but spans two joined relations, which PostgreSQL cannot push down into the
            // ticket indexes: measured at 20,000 rows it produced a Seq Scan over the whole table
            // plus both user tables (414 buffers) instead of an index scan (139). Comparing the FK
            // columns keeps idx_tickets_raised_by_created_at and idx_tickets_assigned_to_created_at
            // usable as a BitmapOr. Hibernate renders an association id on the root as the FK column,
            // with no extra join.
            //
            // assigned_to = ? excludes NULL, which is correct: an unassigned ticket is still reachable
            // through the raised_by side of the OR.
            predicates.add(cb.or(
                    cb.equal(ticket.get("raisedBy").get("id"), restrictToUserId),
                    cb.equal(ticket.get("assignedTo").get("id"), restrictToUserId)));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(Predicate[]::new)));
        }
        query.orderBy(orders(cb, ticket, pageable.getSort()));

        return entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();
    }

    private long countRows(TicketFilter filter, Long restrictToUserId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<Ticket> ticket = query.from(Ticket.class);

        query.select(cb.count(ticket));

        // No joins at all: every predicate compares a column on tickets itself. Comparing an
        // association id on the root renders as the foreign-key column, so there is nothing to join
        // and nothing that could accidentally become an inner join and drop unassigned rows.
        List<Predicate> predicates = new ArrayList<>();
        if (filter.status() != null) {
            predicates.add(cb.equal(ticket.get("status"), filter.status()));
        }
        if (filter.priority() != null) {
            predicates.add(cb.equal(ticket.get("priority"), filter.priority()));
        }
        if (filter.assignedToUserId() != null) {
            predicates.add(cb.equal(ticket.get("assignedTo").get("id"), filter.assignedToUserId()));
        }
        if (filter.raisedByUserId() != null) {
            predicates.add(cb.equal(ticket.get("raisedBy").get("id"), filter.raisedByUserId()));
        }
        if (restrictToUserId != null) {
            predicates.add(cb.or(
                    cb.equal(ticket.get("raisedBy").get("id"), restrictToUserId),
                    cb.equal(ticket.get("assignedTo").get("id"), restrictToUserId)));
        }
        if (!predicates.isEmpty()) {
            query.where(cb.and(predicates.toArray(Predicate[]::new)));
        }

        Long total = entityManager.createQuery(query).getSingleResult();
        return total == null ? 0L : total;
    }

    private List<Predicate> predicates(CriteriaBuilder cb,
                                       Root<Ticket> ticket,
                                       Join<Ticket, User> assignee,
                                       Join<Ticket, User> raiser,
                                       TicketFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.status() != null) {
            predicates.add(cb.equal(ticket.get("status"), filter.status()));
        }
        if (filter.priority() != null) {
            predicates.add(cb.equal(ticket.get("priority"), filter.priority()));
        }
        if (filter.assignedToUserId() != null) {
            // FK column, so idx_tickets_assigned_to_created_at stays usable.
            predicates.add(cb.equal(ticket.get("assignedTo").get("id"), filter.assignedToUserId()));
        }
        if (filter.raisedByUserId() != null) {
            predicates.add(cb.equal(raiser.get("id"), filter.raisedByUserId()));
        }
        return predicates;
    }

    /**
     * Translates the request's sort into criteria orders.
     *
     * <p>Sort properties are whitelisted upstream in {@code TicketPageRequests}, which also guarantees
     * an {@code id} tiebreaker, so ordering here is both safe and deterministic.
     */
    private List<Order> orders(CriteriaBuilder cb, Root<Ticket> ticket, Sort sort) {
        if (sort.isUnsorted()) {
            return List.of(cb.desc(ticket.get("createdAt")), cb.desc(ticket.get("id")));
        }
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Expression<?> expression = sortExpression(cb, ticket, order.getProperty());
            orders.add(order.isAscending() ? cb.asc(expression) : cb.desc(expression));
        }
        return orders;
    }

    /**
     * The expression a sort property orders by.
     *
     * <p>Every property sorts by its own column except {@code priority}, which sorts by severity.
     *
     * <p><strong>Why that needs an expression.</strong> {@code Priority} is mapped
     * {@code @Enumerated(EnumType.STRING)}, so the column holds {@code 'LOW'}, {@code 'MEDIUM'},
     * {@code 'HIGH'}, {@code 'CRITICAL'} and the database sorts it as text. Ascending gave
     * CRITICAL, HIGH, LOW, MEDIUM; {@code ?sort=priority,desc} - the obvious way to ask for "worst
     * first" - gave MEDIUM, LOW, HIGH, CRITICAL, putting the least urgent ticket at the top of a
     * triage queue. Nothing failed and nothing was logged; the list was simply wrong in the one place
     * a support queue is read.
     *
     * <p>Unlike most of the open questions in this codebase this one needs no business decision:
     * {@code Priority} is an ordered scale by its nature, and its declaration order
     * {@code LOW < MEDIUM < HIGH < CRITICAL} already states the severity. The {@code CASE} is built
     * from {@code Priority.values()}, so a new level inserted in the enum is ranked correctly without
     * touching this method.
     *
     * <p><strong>{@code status} is deliberately left alone.</strong> It has the same string-ordering
     * behaviour, but {@code TicketStatus} is not a scale - it is a set of lifecycle states whose legal
     * order is the undecided business question in audit P1-5, and {@code REOPENED} and {@code PENDING}
     * sit at the end of the enum precisely because no sequence was agreed. Ranking it by declaration
     * order would be inventing that sequence. Sorting by status still groups equal statuses together,
     * which is what it is useful for; it just does not claim to be lifecycle order. The same applies
     * to {@code role} on the user listing.
     *
     * <p><strong>No performance change.</strong> Ordering by an expression cannot use an index, but
     * ordering by the {@code priority} column could not either: V12 records that a
     * {@code (priority, created_at DESC, id DESC)} index was built, measured, chosen by the planner
     * zero times and discarded, because four distinct values over 20,000 rows never beat
     * {@code idx_tickets_created_at_id}. This replaces an unindexed column sort with an unindexed
     * expression sort over the same rows.
     */
    private Expression<?> sortExpression(CriteriaBuilder cb, Root<Ticket> ticket, String property) {
        if (!"priority".equals(property)) {
            return ticket.get(property);
        }
        CriteriaBuilder.Case<Integer> severity = cb.selectCase();
        for (Priority level : Priority.values()) {
            severity = severity.when(cb.equal(ticket.get("priority"), level), level.ordinal());
        }
        // Unreachable while the column is constrained to the enum; ranks anything unexpected as the
        // least urgent rather than silently sorting it first.
        return severity.otherwise(Integer.MAX_VALUE);
    }
}
