package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.common.exception.InvalidReferenceException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.dto.request.CreateTicketRequest;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.entity.Ticket;
import com.forward.it_software_support_portal.entity.TicketHistoryTracking;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.IssueType;
import com.forward.it_software_support_portal.enums.Priority;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.enums.TicketStatus;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.repository.TicketHistoryTrackingRepository;
import com.forward.it_software_support_portal.repository.TicketRepository;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.service.impl.TicketServiceImpl;
import com.forward.it_software_support_portal.util.TicketNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two write-path guards on tickets: what a ticket may be raised against, and who it may be
 * assigned to.
 *
 * <p>Both holes were silent. Neither produced an error, a log line or a failing test - they produced a
 * ticket that looked completely normal and could never be worked, which is why the existing
 * characterization suite passed over them. So each case here asserts the refusal <em>and</em> that
 * nothing was written, because "rejected but saved anyway" is the failure mode that would actually
 * hurt (audit finding P0-1 is the same shape).
 *
 * <p>Deliberately a plain Mockito unit test: these are service-layer rules that need no database, and
 * the integration suite requires Docker, which not every machine running this has.
 */
class TicketWriteGuardsTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
    private final TicketHistoryTrackingRepository historyRepository =
            mock(TicketHistoryTrackingRepository.class);
    private final TicketNumberGenerator ticketNumberGenerator = mock(TicketNumberGenerator.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

    private final TicketServiceImpl ticketService = new TicketServiceImpl(
            ticketRepository,
            userRepository,
            applicationRepository,
            historyRepository,
            ticketNumberGenerator,
            currentUserProvider);

    private static final long CALLER_ID = 7L;

    @BeforeEach
    void callerIsAnActiveSupportUser() {
        when(currentUserProvider.requireCurrentUserId()).thenReturn(CALLER_ID);
        when(userRepository.findById(CALLER_ID))
                .thenReturn(Optional.of(user(CALLER_ID, Role.IT_SUPPORT, true)));
        when(ticketNumberGenerator.generate()).thenReturn("TKT-00000001");
    }

    private static User user(long id, Role role, boolean active) {
        User user = new User();
        user.setId(id);
        user.setFullName("User " + id);
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    private static Application application(long id, boolean active) {
        Application application = new Application();
        application.setId(id);
        application.setAppName("Payroll");
        application.setModuleName("Salary");
        application.setActive(active);
        return application;
    }

    private static CreateTicketRequest createRequest(long applicationId) {
        CreateTicketRequest request = new CreateTicketRequest();
        request.setTitle("Cannot generate payslip");
        request.setDescription("The salary module fails on export.");
        request.setIssueType(IssueType.BUG);
        request.setPriority(Priority.HIGH);
        request.setApplicationId(applicationId);
        request.setModuleName("Salary");
        return request;
    }

    private Ticket existingOpenTicket(long id) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNumber("TKT-00000042");
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setIssueType(IssueType.BUG);
        ticket.setPriority(Priority.HIGH);
        ticket.setRaisedBy(user(CALLER_ID, Role.IT_SUPPORT, true));
        ticket.setApplication(application(1L, true));
        return ticket;
    }

    // ------------------------------------------------------------------ #3

    @Test
    @DisplayName("a ticket cannot be raised against a deactivated application")
    void deactivatedApplicationIsRefused() {
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application(1L, false)));

        assertThatThrownBy(() -> ticketService.createTicket(createRequest(1L)))
                .as("""
                        DELETE /api/applications/{id} is a soft deactivation, and \
                        searchActiveApplications is documented as the catalogue a ticket may be raised \
                        against. Nothing enforced it on the write path, so a cached dropdown or a \
                        direct call could keep filing against a retired system.""")
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("no longer active");

        verify(ticketRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("a ticket against an active application is still created, with its audit row")
    void activeApplicationIsAccepted() {
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(application(1L, true)));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setId(100L);
            return ticket;
        });

        assertThatCode(() -> ticketService.createTicket(createRequest(1L))).doesNotThrowAnyException();

        verify(ticketRepository).save(any(Ticket.class));
        verify(historyRepository).save(any(TicketHistoryTracking.class));
    }

    // ------------------------------------------------------------------ #4

    @Test
    @DisplayName("a ticket cannot be assigned to a deactivated account")
    void deactivatedAssigneeIsRefused() {
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(existingOpenTicket(42L)));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, Role.IT_SUPPORT, false)));

        assertThatThrownBy(() -> ticketService.assignTicket(42L, 9L))
                .as("an inactive user cannot authenticate, so the ticket would be parked with "
                        + "somebody who cannot sign in to see it")
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("deactivated");

        verify(ticketRepository, never()).save(any());
        verify(historyRepository, never()).save(any());
    }

    @Test
    @DisplayName("an active EMPLOYEE is still a valid assignee - the guard is about activity, not role")
    void requesterRoleAssigneeIsStillAccepted() {
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(existingOpenTicket(42L)));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, Role.EMPLOYEE, true)));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> ticketService.assignTicket(42L, 9L))
                .as("""
                        Pins the boundary of this guard. Requiring the assignee to hold \
                        TICKET_STATUS_CHANGE was written and deliberately removed: TICKET_READ_OWN is \
                        documented as "raiser or assignee" for any role, TicketRow.involves implements \
                        exactly that, and ResourceAccessControlTest assigns to an EMPLOYEE on purpose \
                        and asserts they can then read and list the ticket. Assignment to a requester \
                        is established behaviour, so restricting it is a business decision - not \
                        something to re-add here. See requireAssignable.""")
                .doesNotThrowAnyException();

        verify(ticketRepository).save(any(Ticket.class));
    }

    @Test
    @DisplayName("assignment to an active support user still works and still forces ASSIGNED")
    void supportAssigneeIsAccepted() {
        Ticket ticket = existingOpenTicket(42L);
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(ticket));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, Role.DEVELOPER, true)));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> ticketService.assignTicket(42L, 9L)).doesNotThrowAnyException();

        assertThat(ticket.getAssignedTo().getId()).isEqualTo(9L);
        assertThat(ticket.getStatus())
                .as("""
                        unchanged behaviour: whether reassignment should reset status is audit P2-12, \
                        still an open business decision. These guards narrow who may be assigned; they \
                        do not change what assignment does.""")
                .isEqualTo(TicketStatus.ASSIGNED);
    }

    @Test
    @DisplayName("ADMIN can be assigned a ticket")
    void adminAssigneeIsAccepted() {
        when(ticketRepository.findById(42L)).thenReturn(Optional.of(existingOpenTicket(42L)));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L, Role.ADMIN, true)));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> ticketService.assignTicket(42L, 9L)).doesNotThrowAnyException();
    }
}
