package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.common.exception.InvalidReferenceException;
import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.dto.request.TicketFilter;
import com.forward.it_software_support_portal.dto.response.TicketHistoryResponse;
import com.forward.it_software_support_portal.dto.response.TicketResponse;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.entity.TicketHistoryTracking;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.repository.projection.TicketHistoryRow;
import com.forward.it_software_support_portal.repository.projection.TicketRow;
import com.forward.it_software_support_portal.security.AuthenticatedCurrentUserProvider;
import com.forward.it_software_support_portal.security.Permission;
import com.forward.it_software_support_portal.repository.TicketHistoryTrackingRepository;
import com.forward.it_software_support_portal.repository.TicketRepository;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.service.TicketService;
import com.forward.it_software_support_portal.util.TicketNumberGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;


@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketServiceImpl.class);

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final TicketHistoryTrackingRepository ticketHistoryTrackingRepository;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final CurrentUserProvider currentUserProvider;

    // Overall Flow:
    // OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED
    //
    // NOTE: this comment describes an intended happy path only. It is NOT enforced, and it does not
    // define which transitions are legal in general (for example whether CLOSED may return to OPEN,
    // or which states REOPENED may be entered from). No specification, acceptance criteria, CHECK
    // constraint or test establishes those rules, and UNDER_REVIEW, PENDING and REOPENED appear
    // nowhere in this class. Introducing a transition table would therefore mean inventing business
    // rules, so Phase 2 deliberately leaves transitions unvalidated - see docs/ARCHITECTURE_AUDIT.md
    // finding P1-5 and the open question recorded in docs/TESTING.md.

    /**
     * Creates a ticket together with its {@code CREATED} audit row, atomically.
     *
     * <p>{@code @Transactional} means the ticket insert and the audit insert either both commit or
     * neither does. Previously there was no transaction at all, so the two writes were independent
     * (audit finding P0-1).
     *
     * <p>The raiser is now the calling user rather than the hardcoded {@code findById(1L)}
     * (audit finding P0-3).
     *
     * <p>The chosen application must be active - see {@link #requireActiveApplication}.
     */
    @Override
    @Transactional
    public TicketResponse createTicket(CreateTicketRequest request) {

        User raisedBy = requireCurrentUser();

        Application application = applicationRepository.findById(request.getApplicationId())
                .orElseThrow(() -> InvalidReferenceException.of("applicationId", request.getApplicationId()));

        requireActiveApplication(application);

        Ticket ticket = new Ticket();
        ticket.setTicketNumber(ticketNumberGenerator.generate());
        ticket.setTitle(request.getTitle());
        ticket.setDescription(request.getDescription());
        ticket.setIssueType(request.getIssueType());
        ticket.setPriority(request.getPriority());
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setBusinessImpact(request.getBusinessImpact());
        ticket.setExpectedBy(request.getExpectedBy());
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticket.setRaisedBy(raisedBy);
        ticket.setApplication(application);
        ticket.setModuleName(request.getModuleName());

        Ticket saved = ticketRepository.save(ticket);

        saveHistory(
                saved,
                "CREATED",
                "status",
                null,
                TicketStatus.OPEN.name(),
                raisedBy,
                "Ticket created successfully"
        );

        return mapToResponse(saved);
    }

    /**
     * Assigns a ticket and records both audit rows, atomically.
     *
     * <p>The status is still forced to {@code ASSIGNED} exactly as before. Whether reassigning an
     * {@code IN_PROGRESS} ticket should reset it is an open business question with no authoritative
     * answer in this codebase, so Phase 2 leaves the behaviour untouched (audit finding P2-12).
     *
     * <p>One audit correction: {@code changed_by} now records the user who performed the assignment
     * rather than the user who received it. The column means "who changed this", and attributing the
     * change to the assignee made the trail wrong.
     *
     * <p>The assignee must be someone who can actually work the ticket - see
     * {@link #requireAssignable}.
     */
    @Override
    @Transactional
    public TicketResponse assignTicket(Long ticketId, Long userId) {

        User actor = requireCurrentUser();

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));

        User newAssignedUser = userRepository.findById(userId)
                .orElseThrow(() -> InvalidReferenceException.of("assigned user id", userId));

        requireAssignable(newAssignedUser);

        String oldAssignedUser = ticket.getAssignedTo() != null
                ? ticket.getAssignedTo().getFullName()
                : null;

        String oldStatus = ticket.getStatus().name();

        ticket.setAssignedTo(newAssignedUser);
        ticket.setStatus(TicketStatus.ASSIGNED);
        ticket.setUpdatedAt(LocalDateTime.now());

        Ticket updatedTicket = ticketRepository.save(ticket);

        saveHistory(
                updatedTicket,
                "ASSIGNED",
                "assigned_to",
                oldAssignedUser,
                newAssignedUser.getFullName(),
                actor,
                "Ticket assigned to user"
        );

        saveHistory(
                updatedTicket,
                "STATUS_CHANGED",
                "status",
                oldStatus,
                TicketStatus.ASSIGNED.name(),
                actor,
                "Status changed automatically on assignment"
        );

        return mapToResponse(updatedTicket);
    }

    /**
     * Changes a ticket's status and records the change, atomically.
     *
     * <p>This method carried the defect that motivated Phase 2. It used to read
     * {@code ticket.getAssignedTo().getId()} to decide who to attribute the audit row to, which threw
     * a {@code NullPointerException} for any unassigned ticket - that is, for the normal first
     * transition of every ticket. Because nothing was transactional, the status save had already
     * committed by then, so the caller got HTTP 500 while the change was durably applied and no audit
     * row was ever written (audit findings P0-1 and P0-2).
     *
     * <p>Both halves are fixed: the actor comes from {@link CurrentUserProvider} instead of the
     * assignee, so an unassigned ticket is no longer a special case, and {@code @Transactional} makes
     * the status change and its audit row succeed or fail together.
     *
     * <p>Transitions are still not validated - see the class comment.
     */
    @Override
    @Transactional
    public TicketResponse updateStatus(Long ticketId, TicketStatus status) {

        User actor = requireCurrentUser();

        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));

        String oldStatus = ticket.getStatus().name();

        ticket.setStatus(status);
        ticket.setUpdatedAt(LocalDateTime.now());
        applyResolutionTimestamp(ticket, status);

        Ticket updatedTicket = ticketRepository.save(ticket);

        saveHistory(
                updatedTicket,
                "STATUS_CHANGED",
                "status",
                oldStatus,
                status.name(),
                actor,
                "Ticket status updated"
        );

        return mapToResponse(updatedTicket);
    }

    /**
     * Reads one ticket through a projection, so no managed entity enters the persistence context.
     */
    /**
     * Reads one ticket, enforcing resource-level authorization.
     *
     * <p>This is the IDOR/BOLA boundary for a single ticket. A caller holding
     * {@code TICKET_READ_ALL} - support staff - sees any ticket. Everyone else sees only tickets they
     * raised or are assigned to, so changing the id in the URL reveals nothing.
     *
     * <p><strong>It answers 404, not 403, for someone else's ticket.</strong> A 403 would confirm that
     * the ticket exists, which lets an unauthorised caller map out valid ids by probing. From the
     * caller's point of view a ticket they may not see is indistinguishable from one that does not
     * exist, which is exactly the intended amount of information.
     *
     * <p>The check costs no extra query: the ownership ids arrive on the same projection row.
     */
    @Override
    @Transactional(readOnly = true)
    public TicketResponse getTicketById(Long id) {
        TicketRow row = ticketRepository.findTicketRowById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", id));

        requireVisibility(row, id);
        return mapRow(row);
    }

    /**
     * Fails as "not found" when the caller may not see this ticket.
     *
     * @throws ResourceNotFoundException deliberately, rather than an access-denied exception - see
     *                                   {@link #getTicketById}
     */
    private void requireVisibility(TicketRow row, Long ticketId) {
        if (AuthenticatedCurrentUserProvider.currentUserHas(Permission.TICKET_READ_ALL)) {
            return;
        }
        Long callerId = currentUserProvider.requireCurrentUserId();
        if (!row.involves(callerId)) {
            log.info("User {} was denied access to ticket {} (not raiser or assignee)",
                    callerId, ticketId);
            throw ResourceNotFoundException.of("Ticket", ticketId);
        }
    }

    /**
     * Paged, filtered list backed by a single projection query (audit finding P1-1).
     *
     * <p>The previous implementation was {@code findAll().stream().map(...)}: unbounded, and loading
     * entities whose eager to-one associations triggered a follow-up select per distinct referenced
     * user and application. Measured worst case was 401 statements for 200 tickets.
     *
     * <p>{@code readOnly = true} lets Hibernate skip dirty checking, and with a projection there is
     * nothing to dirty-check in the first place.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<TicketResponse> searchTickets(TicketFilter filter, Pageable pageable) {
        TicketFilter effective = filter == null ? TicketFilter.none() : filter;

        // The collection half of the IDOR boundary. Support staff (TICKET_READ_ALL) list everything;
        // everyone else gets an ownership predicate pushed into the SQL, so they cannot see another
        // user's tickets - and cannot learn how many exist by filtering for them either, because the
        // restriction is ANDed with their filters rather than applied afterwards in memory.
        Long restrictToUserId =
                AuthenticatedCurrentUserProvider.currentUserHas(Permission.TICKET_READ_ALL)
                        ? null
                        : currentUserProvider.requireCurrentUserId();

        return ticketRepository.findTicketRows(effective, pageable, restrictToUserId)
                .map(TicketServiceImpl::mapRow);
    }

    /**
     * One page of a ticket's audit trail (see {@link TicketService#getTicketHistory}).
     *
     * <p>The ticket row is loaded first for one reason only: to run {@link #requireVisibility} against
     * it. Reusing that method rather than writing a second ownership rule here is the point - a
     * resource-level check that exists in two places is a check that eventually disagrees with itself,
     * and the half that is wrong is the one nobody notices. A caller who may not read the ticket
     * therefore gets the same 404 for its history, with no way to tell the two cases apart.
     *
     * <p>Costs one extra query for that check. Worth it: the alternative is an endpoint that leaks
     * whether a ticket exists, through its history, to anyone who can guess an id.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<TicketHistoryResponse> getTicketHistory(Long ticketId, Pageable pageable) {
        TicketRow row = ticketRepository.findTicketRowById(ticketId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", ticketId));

        requireVisibility(row, ticketId);

        return ticketHistoryTrackingRepository.findHistoryRows(ticketId, pageable)
                .map(TicketServiceImpl::mapHistoryRow);
    }

    private static TicketHistoryResponse mapHistoryRow(TicketHistoryRow row) {
        return TicketHistoryResponse.builder()
                .id(row.id())
                .actionType(row.actionType())
                .fieldName(row.fieldName())
                .oldValue(row.oldValue())
                .newValue(row.newValue())
                .changedBy(row.changedByFullName())
                .remarks(row.remarks())
                .changedAt(row.changedAt())
                .build();
    }

    /**
     * Resolves the calling user and confirms they exist.
     *
     * <p>A caller identity that does not match a real user is a client error, not a server fault:
     * {@code changed_by} and {@code raised_by} are foreign keys, so the write would be rejected by
     * the database anyway.
     */
    private User requireCurrentUser() {
        Long userId = currentUserProvider.requireCurrentUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> InvalidReferenceException.of(
                        "authenticated user id", userId));
    }

    /**
     * Refuses a ticket raised against a deactivated application.
     *
     * <p>This enforces a rule the codebase already stated but never applied. {@code Application.active}
     * exists, {@code DELETE /api/applications/{id}} is a soft deactivation rather than a delete, and
     * {@code ApplicationService.searchActiveApplications} is documented as "the catalogue a ticket may
     * be raised against". Nothing checked it on the write path, so deactivating an application only
     * removed it from one listing: a client that already knew the id - a cached dropdown, a bookmarked
     * form, a direct API call - could keep filing against a retired system indefinitely, and those
     * tickets then sat in a queue nobody owned.
     *
     * <p>Deliberately <strong>not</strong> extended to reassignment or status changes of tickets that
     * already exist. Deactivating an application must not strand the tickets already raised against it;
     * support still has to work them to a close. The rule is about creating new ones.
     *
     * @throws InvalidReferenceException (400) because the URL was right and a value in the body was
     *                                   wrong - the same reading as an unknown {@code applicationId}
     */
    /**
     * Keeps {@code resolved_at} consistent with the status (audit finding P1-6).
     *
     * <p>Setting the timestamp on {@code RESOLVED} was already here; <strong>clearing it was not</strong>,
     * so a ticket that was resolved and then reopened kept the timestamp it earned earlier. It then
     * counted as resolved in every "resolved this week", MTTR and SLA query while sitting in
     * {@code REOPENED} - a wrong number rather than a visible error, which is why nothing caught it.
     *
     * <p>Three states, by what the status means rather than by naming transitions:
     *
     * <ul>
     *   <li>{@code RESOLVED} - stamp the moment of resolution. Re-entering {@code RESOLVED} after a
     *       reopen re-stamps, so the column answers "when was this last resolved"; the full trail is in
     *       {@code ticket_history_tracking}, which is where the audit says resolution metrics belong.
     *   <li>{@code CLOSED} - leave whatever is there. A closed ticket was resolved, and wiping the
     *       timestamp at the final step of the documented happy path would destroy the very metric this
     *       fix exists to protect. A ticket closed without ever being resolved simply keeps its
     *       {@code null}, which correctly reports "closed, never resolved".
     *   <li>anything else - the ticket is open work again, so the timestamp is wrong and is cleared.
     * </ul>
     *
     * <p><strong>This is not the workflow decision.</strong> Which transitions are <em>legal</em>
     * (P1-5) is still undecided and still unenforced - see the class comment. This method only keeps a
     * derived column honest about whatever status the ticket actually reached, which holds under any
     * workflow the business eventually picks.
     */
    private static void applyResolutionTimestamp(Ticket ticket, TicketStatus status) {
        if (status == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(LocalDateTime.now());
        } else if (status != TicketStatus.CLOSED) {
            ticket.setResolvedAt(null);
        }
    }

    private void requireActiveApplication(Application application) {
        if (!application.isActive()) {
            log.info("Rejected ticket creation against inactive application {}", application.getId());
            throw new InvalidReferenceException(
                    "applicationId refers to an application that is no longer active: "
                            + application.getId());
        }
    }

    /**
     * Refuses an assignment to a deactivated account.
     *
     * <p>Not a business decision: {@code AuthServiceImpl} already refuses to authenticate an inactive
     * user, so assigning a ticket to one parks it with somebody who cannot sign in to see it. The
     * ticket stays {@code ASSIGNED} indefinitely and disappears from every "unassigned work" view,
     * which is worse than leaving it unassigned - it looks owned.
     *
     * <p><strong>REJECTED CANDIDATE, recorded so it is not re-proposed.</strong> A second check was
     * written here and removed: requiring the assignee's role to hold
     * {@link Permission#TICKET_STATUS_CHANGE}, on the reasoning that a requester-role assignee can see
     * a ticket but never progress it. It is wrong, and the codebase already says so in two places:
     *
     * <ul>
     *   <li>{@link Permission#TICKET_READ_OWN} is documented as "read tickets the user is involved in,
     *       as raiser <em>or as assignee</em>", and {@code TicketRow.involves} implements that for any
     *       user regardless of role. If only status-changers could be assignees, the assignee half of
     *       that check would be unreachable for every requester role.
     *   <li>{@code ResourceAccessControlTest} assigns a ticket to an {@code EMPLOYEE} on purpose and
     *       asserts the assignee can then read and list it ("being the assignee is involvement; they
     *       have to be able to see their work").
     * </ul>
     *
     * <p>So assignment to a requester is an established, tested behaviour, not an oversight -
     * plausibly "this needs something from you". Narrowing it would have been inventing a workflow
     * rule and breaking a passing security test to do it. If the business does want assignment
     * restricted to people who can work tickets, that is a decision to record alongside the others in
     * docs/SECURITY.md §12, and those two tests change with it.
     *
     * @throws InvalidReferenceException (400) - same family as an unknown assignee id, which this
     *                                  method sits directly after
     */
    private void requireAssignable(User assignee) {
        if (!Boolean.TRUE.equals(assignee.getActive())) {
            log.info("Rejected assignment to deactivated user {}", assignee.getId());
            throw new InvalidReferenceException(
                    "assigned user id refers to a deactivated account: " + assignee.getId());
        }
    }

    /**
     * Maps a read projection to the response DTO.
     *
     * <p>The DTO's field names and types are unchanged - only where the data comes from has changed.
     */
    private static TicketResponse mapRow(TicketRow row) {
        return TicketResponse.builder()
                .id(row.id())
                .ticketNumber(row.ticketNumber())
                .title(row.title())
                .description(row.description())
                .issueType(row.issueType().name())
                .priority(row.priority().name())
                .status(row.status().name())
                .businessImpact(row.businessImpact())
                .expectedBy(row.expectedBy())
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .resolvedAt(row.resolvedAt())
                .raisedBy(row.raisedByFullName())
                .assignedTo(row.assignedToFullName())
                .applicationName(row.applicationName())
                .build();
    }

    /**
     * Entity-based mapping, still used by the write paths, which legitimately hold the entity in
     * order to mutate it and to let {@code @Version} do its work.
     */
    private TicketResponse mapToResponse(Ticket ticket) {
        return TicketResponse.builder()
                .id(ticket.getId())
                .ticketNumber(ticket.getTicketNumber())
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .issueType(ticket.getIssueType().name())
                .priority(ticket.getPriority().name())
                .status(ticket.getStatus().name())
                .businessImpact(ticket.getBusinessImpact())
                .expectedBy(ticket.getExpectedBy())
                .createdAt(ticket.getCreatedAt())
                .updatedAt(ticket.getUpdatedAt())
                .resolvedAt(ticket.getResolvedAt())
                .raisedBy(ticket.getRaisedBy().getFullName())
                // Restored (audit finding P1-7). This line was commented out, so the API reported
                // assignedTo as null even when the database held an assignee - an engineer reading
                // the API saw an unassigned ticket that was in fact assigned. Null-safe because an
                // unassigned ticket is legitimate.
                .assignedTo(ticket.getAssignedTo() != null
                        ? ticket.getAssignedTo().getFullName()
                        : null)
                .applicationName(ticket.getApplication().getAppName())
                .build();
    }

    /**
     * Appends one audit row.
     *
     * <p>Private on purpose. It is called from methods that are already {@code @Transactional}, so it
     * joins the caller's transaction - there is no self-invocation problem to solve here, because no
     * separate transaction is wanted. Annotating this method would in fact be the bug: a
     * {@code REQUIRES_NEW} audit write would commit independently and reintroduce exactly the partial
     * state that Phase 2 removes.
     *
     * <p>It now takes the already-loaded {@link Ticket} and actor instead of re-fetching them by id,
     * which also removes two redundant lookups per write.
     */
    private void saveHistory(
            Ticket ticket,
            String actionType,
            String fieldName,
            String oldValue,
            String newValue,
            User changedBy,
            String remarks
    ) {
        TicketHistoryTracking history = new TicketHistoryTracking();
        history.setTicket(ticket);
        history.setActionType(actionType);
        history.setFieldName(fieldName);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setChangedBy(changedBy);
        history.setRemarks(remarks);
        history.setChangedAt(LocalDateTime.now());

        ticketHistoryTrackingRepository.save(history);
    }
}
