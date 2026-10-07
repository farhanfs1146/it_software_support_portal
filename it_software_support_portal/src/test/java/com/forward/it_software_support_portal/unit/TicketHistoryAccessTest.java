package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.dto.response.TicketHistoryResponse;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.repository.TicketHistoryTrackingRepository;
import com.forward.it_software_support_portal.repository.TicketRepository;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.repository.projection.TicketHistoryRow;
import com.forward.it_software_support_portal.repository.projection.TicketRow;
import com.forward.it_software_support_portal.security.Permission;
import com.forward.it_software_support_portal.service.impl.TicketServiceImpl;
import com.forward.it_software_support_portal.util.TicketNumberGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Access control on the ticket audit trail, added with the endpoint that finally exposes it.
 *
 * <p>The trail is ticket data, so it inherits the ticket's visibility rule rather than getting one of
 * its own - otherwise {@code GET /api/tickets/{id}} would answer 404 for someone else's ticket while
 * {@code GET /api/tickets/{id}/history} quietly confirmed it exists. These tests exist mainly to pin
 * that down: a new read endpoint over existing data is the usual way an IDOR boundary springs a leak.
 *
 * <p>Plain unit test - the rule lives in the service, and the integration suite needs Docker.
 */
class TicketHistoryAccessTest {

    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final TicketHistoryTrackingRepository historyRepository =
            mock(TicketHistoryTrackingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);

    private final TicketServiceImpl ticketService = new TicketServiceImpl(
            ticketRepository,
            mock(UserRepository.class),
            mock(ApplicationRepository.class),
            historyRepository,
            mock(TicketNumberGenerator.class),
            currentUserProvider);

    private static final long TICKET_ID = 42L;
    private static final long RAISER_ID = 1L;
    private static final long ASSIGNEE_ID = 2L;
    private static final long STRANGER_ID = 99L;

    private static final Pageable PAGE = PageRequest.of(0, 20);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates a caller holding exactly the given permissions. */
    private void callerWith(Permission... permissions) {
        String[] authorities = new String[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            authorities[i] = permissions[i].name();
        }
        TestingAuthenticationToken authentication =
                new TestingAuthenticationToken("caller", "n/a", authorities);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static TicketRow ticketRow() {
        return new TicketRow(
                TICKET_ID, "TKT-00000042", "Payslip fails", "Export breaks",
                null, null, null, null, null, null, null, null,
                "Raiser", "Assignee", "Payroll",
                RAISER_ID, ASSIGNEE_ID);
    }

    private void historyExists() {
        TicketHistoryRow row = new TicketHistoryRow(
                7L, "STATUS_CHANGED", "status", "OPEN", "ASSIGNED",
                "Acting User", "Status changed automatically on assignment",
                LocalDateTime.of(2026, 10, 1, 9, 30));
        when(historyRepository.findHistoryRows(eq(TICKET_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row)));
    }

    @Test
    @DisplayName("support staff can read any ticket's history, and the row is mapped faithfully")
    void supportReadsAnyHistory() {
        callerWith(Permission.TICKET_READ_OWN, Permission.TICKET_READ_ALL);
        when(ticketRepository.findTicketRowById(TICKET_ID)).thenReturn(Optional.of(ticketRow()));
        historyExists();

        Page<TicketHistoryResponse> result = ticketService.getTicketHistory(TICKET_ID, PAGE);

        assertThat(result.getContent()).singleElement().satisfies(entry -> {
            assertThat(entry.getActionType()).isEqualTo("STATUS_CHANGED");
            assertThat(entry.getFieldName()).isEqualTo("status");
            assertThat(entry.getOldValue()).isEqualTo("OPEN");
            assertThat(entry.getNewValue()).isEqualTo("ASSIGNED");
            assertThat(entry.getChangedBy())
                    .as("the actor is reported by name, as TicketResponse reports people")
                    .isEqualTo("Acting User");
            assertThat(entry.getChangedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 30));
        });
    }

    @Test
    @DisplayName("the raiser can read their own ticket's history")
    void raiserReadsOwnHistory() {
        callerWith(Permission.TICKET_READ_OWN);
        when(currentUserProvider.requireCurrentUserId()).thenReturn(RAISER_ID);
        when(ticketRepository.findTicketRowById(TICKET_ID)).thenReturn(Optional.of(ticketRow()));
        historyExists();

        assertThat(ticketService.getTicketHistory(TICKET_ID, PAGE).getContent()).hasSize(1);
    }

    @Test
    @DisplayName("the assignee can read the history of a ticket they did not raise")
    void assigneeReadsHistory() {
        callerWith(Permission.TICKET_READ_OWN);
        when(currentUserProvider.requireCurrentUserId()).thenReturn(ASSIGNEE_ID);
        when(ticketRepository.findTicketRowById(TICKET_ID)).thenReturn(Optional.of(ticketRow()));
        historyExists();

        assertThat(ticketService.getTicketHistory(TICKET_ID, PAGE).getContent()).hasSize(1);
    }

    @Test
    @DisplayName("an uninvolved caller gets 404, and the trail is never queried")
    void strangerIsRefusedWithNotFound() {
        callerWith(Permission.TICKET_READ_OWN);
        when(currentUserProvider.requireCurrentUserId()).thenReturn(STRANGER_ID);
        when(ticketRepository.findTicketRowById(TICKET_ID)).thenReturn(Optional.of(ticketRow()));

        assertThatThrownBy(() -> ticketService.getTicketHistory(TICKET_ID, PAGE))
                .as("""
                        404 rather than 403, matching getTicketById: a 403 would confirm the ticket \
                        exists and let an unauthorised caller map valid ids by probing. An empty page \
                        would leak the same thing.""")
                .isInstanceOf(ResourceNotFoundException.class);

        verify(historyRepository, never()).findHistoryRows(any(), any());
    }

    @Test
    @DisplayName("a ticket that does not exist gets 404 from the same path")
    void missingTicketIsNotFound() {
        callerWith(Permission.TICKET_READ_OWN, Permission.TICKET_READ_ALL);
        when(ticketRepository.findTicketRowById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.getTicketHistory(TICKET_ID, PAGE))
                .as("indistinguishable from the refusal above, which is the point")
                .isInstanceOf(ResourceNotFoundException.class);

        verify(historyRepository, never()).findHistoryRows(any(), any());
    }
}
