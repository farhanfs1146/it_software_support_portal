package com.forward.it_software_support_portal.unit;

import com.forward.it_software_support_portal.common.exception.InvalidReferenceException;
import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.dto.request.ChangePasswordRequest;
import com.forward.it_software_support_portal.dto.request.ResetPasswordRequest;
import com.forward.it_software_support_portal.dto.request.UpdateUserRoleRequest;
import com.forward.it_software_support_portal.dto.request.UpdateUserStatusRequest;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.session.RefreshTokenService;
import com.forward.it_software_support_portal.security.session.RevocationReason;
import com.forward.it_software_support_portal.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The administrative account rules: who may be deactivated, demoted or reset, and what each of those
 * does to the target's live sessions.
 *
 * <p>These are the rules that stop the organization locking itself out, so they are asserted directly
 * on the service rather than only through HTTP - a Docker-free test, verifiable on a machine with no
 * container runtime.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Administrative account controls")
class AccountAdministrationGuardsTest {

    private static final long ADMIN_ID = 1L;
    private static final long TARGET_ID = 2L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    private UserServiceImpl service;
    private User target;
    private User actingAdmin;

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(
                userRepository, passwordEncoder, currentUserProvider, refreshTokenService);

        actingAdmin = user(ADMIN_ID, Role.ADMIN, true);
        target = user(TARGET_ID, Role.EMPLOYEE, true);

        when(currentUserProvider.requireCurrentUserId()).thenReturn(ADMIN_ID);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(actingAdmin));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(2L);
        when(passwordEncoder.encode(any())).thenReturn("$2a$10$hashed");
        when(passwordEncoder.matches(any(), any())).thenReturn(true);
    }

    // ------------------------------------------------------------ deactivation

    @Test
    @DisplayName("deactivating a user ends every session they hold")
    void deactivationEndsAllSessions() {
        service.updateStatus(TARGET_ID, status(false));

        assertThat(target.getActive()).isFalse();
        verify(refreshTokenService).revokeAllSessions(
                target, RevocationReason.ACCOUNT_DEACTIVATED);
    }

    @Test
    @DisplayName("you cannot deactivate your own account")
    void cannotDeactivateSelf() {
        assertThatThrownBy(() -> service.updateStatus(ADMIN_ID, status(false)))
                .describedAs("immediately self-inflicted: the next request fails and nobody can "
                        + "re-enable the account")
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("cannot deactivate your own account");

        assertThat(actingAdmin.getActive()).isTrue();
        verify(refreshTokenService, never()).revokeAllSessions(any(), any());
    }

    @Test
    @DisplayName("you cannot deactivate the last active administrator")
    void cannotDeactivateLastAdministrator() {
        User otherAdmin = user(3L, Role.ADMIN, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateStatus(3L, status(false)))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("last active administrator");

        assertThat(otherAdmin.getActive()).isTrue();
    }

    @Test
    @DisplayName("an administrator can be deactivated while another active one remains")
    void canDeactivateAdministratorWhenAnotherRemains() {
        User otherAdmin = user(3L, Role.ADMIN, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(2L);

        assertThatCode(() -> service.updateStatus(3L, status(false))).doesNotThrowAnyException();
        assertThat(otherAdmin.getActive()).isFalse();
    }

    @Test
    @DisplayName("reactivating revokes nothing, because an inactive account holds no sessions")
    void reactivationRevokesNothing() {
        target.setActive(false);

        service.updateStatus(TARGET_ID, status(true));

        assertThat(target.getActive()).isTrue();
        verify(refreshTokenService, never()).revokeAllSessions(any(), any());
    }

    @Test
    @DisplayName("setting the status to its current value changes and revokes nothing")
    void noOpStatusChangeIsHarmless() {
        service.updateStatus(TARGET_ID, status(true));

        assertThat(target.getActive()).isTrue();
        verify(refreshTokenService, never()).revokeAllSessions(any(), any());
    }

    // -------------------------------------------------------------- role change

    @Test
    @DisplayName("changing a role ends every session, so new authorities apply at once")
    void roleChangeEndsAllSessions() {
        service.updateRole(TARGET_ID, role(Role.IT_SUPPORT));

        assertThat(target.getRole()).isEqualTo(Role.IT_SUPPORT);
        verify(refreshTokenService).revokeAllSessions(target, RevocationReason.ROLE_CHANGED);
    }

    @Test
    @DisplayName("you cannot change your own role")
    void cannotChangeOwnRole() {
        assertThatThrownBy(() -> service.updateRole(ADMIN_ID, role(Role.EMPLOYEE)))
                .describedAs("blocks both self-escalation and the self-demotion that strips the "
                        + "permission needed to undo it")
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("cannot change your own role");

        assertThat(actingAdmin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("you cannot demote the last active administrator")
    void cannotDemoteLastAdministrator() {
        User otherAdmin = user(3L, Role.ADMIN, true);
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));
        when(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRole(3L, role(Role.EMPLOYEE)))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("last active administrator");

        assertThat(otherAdmin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("promoting someone to ADMIN is never blocked by the last-administrator rule")
    void promotionIsNeverBlocked() {
        when(userRepository.countByRoleAndActiveTrue(Role.ADMIN)).thenReturn(1L);

        assertThatCode(() -> service.updateRole(TARGET_ID, role(Role.ADMIN)))
                .describedAs("the guard protects against removing the last admin, not adding one")
                .doesNotThrowAnyException();
        assertThat(target.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("assigning the role a user already holds changes and revokes nothing")
    void noOpRoleChangeIsHarmless() {
        service.updateRole(TARGET_ID, role(Role.EMPLOYEE));

        verify(refreshTokenService, never()).revokeAllSessions(any(), any());
    }

    // ----------------------------------------------------------- password reset

    @Test
    @DisplayName("an administrative reset rehashes the password and ends every session")
    void resetRehashesAndEndsSessions() {
        service.resetPassword(TARGET_ID, reset("a-brand-new-password"));

        verify(passwordEncoder).encode("a-brand-new-password");
        assertThat(target.getPasswordHash()).isEqualTo("$2a$10$hashed");
        verify(refreshTokenService).revokeAllSessions(target, RevocationReason.PASSWORD_RESET);
    }

    @Test
    @DisplayName("resetting an unknown user is a 404, not a silent no-op")
    void resetOfUnknownUserIsNotFound() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(404L, reset("a-brand-new-password")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------ own password

    @Test
    @DisplayName("changing your own password ends every session, including the current one")
    void ownPasswordChangeEndsAllSessions() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("the-old-password");
        request.setNewPassword("the-new-password");
        actingAdmin.setPasswordHash("$2a$10$existing");

        service.changeOwnPassword(request);

        verify(refreshTokenService).revokeAllSessions(
                eq(actingAdmin), eq(RevocationReason.PASSWORD_CHANGED));
    }

    @Test
    @DisplayName("a failed password change revokes nothing")
    void failedPasswordChangeRevokesNothing() {
        when(passwordEncoder.matches(any(), any())).thenReturn(false);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("wrong");
        request.setNewPassword("the-new-password");
        actingAdmin.setPasswordHash("$2a$10$existing");

        assertThatThrownBy(() -> service.changeOwnPassword(request)).isInstanceOf(RuntimeException.class);

        verify(refreshTokenService, never()).revokeAllSessions(any(), any());
    }

    // ------------------------------------------------------- forced sign-out

    @Test
    @DisplayName("an administrator can force a sign-out without touching the password or role")
    void forcedSignOutLeavesTheAccountUsable() {
        when(refreshTokenService.revokeAllSessions(target, RevocationReason.ADMIN_REVOKED))
                .thenReturn(3);

        assertThat(service.revokeSessions(TARGET_ID)).isEqualTo(3);
        assertThat(target.getRole()).isEqualTo(Role.EMPLOYEE);
        assertThat(target.getActive()).isTrue();
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("signing yourself out of everything is allowed")
    void forcedSignOutMayTargetSelf() {
        assertThatCode(() -> service.revokeSessions(ADMIN_ID))
                .describedAs("nothing to recover from - it is what POST /api/auth/logout-all does")
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ helpers

    private static UpdateUserStatusRequest status(boolean active) {
        UpdateUserStatusRequest request = new UpdateUserStatusRequest();
        request.setActive(active);
        return request;
    }

    private static UpdateUserRoleRequest role(Role role) {
        UpdateUserRoleRequest request = new UpdateUserRoleRequest();
        request.setRole(role);
        return request;
    }

    private static ResetPasswordRequest reset(String newPassword) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setNewPassword(newPassword);
        return request;
    }

    private static User user(Long id, Role role, boolean active) {
        User user = new User();
        user.setId(id);
        user.setEmployeeCode(1000L + id);
        user.setFullName("User " + id);
        user.setEmail("user" + id + "@example.test");
        user.setRole(role);
        user.setActive(active);
        user.setTokenVersion(0);
        return user;
    }
}
