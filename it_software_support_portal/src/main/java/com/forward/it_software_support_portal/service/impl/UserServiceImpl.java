package com.forward.it_software_support_portal.service.impl;


import com.forward.it_software_support_portal.common.exception.DuplicateResourceException;
import com.forward.it_software_support_portal.common.exception.InvalidReferenceException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.security.AuthenticatedCurrentUserProvider;
import com.forward.it_software_support_portal.security.Permission;
import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.dto.request.ChangePasswordRequest;
import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.request.ResetPasswordRequest;
import com.forward.it_software_support_portal.dto.request.UpdateUserRoleRequest;
import com.forward.it_software_support_portal.dto.request.UpdateUserStatusRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.enums.Role;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
import com.forward.it_software_support_portal.security.session.RefreshTokenService;
import com.forward.it_software_support_portal.security.session.RevocationReason;
import com.forward.it_software_support_portal.service.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserProvider currentUserProvider;
    private final RefreshTokenService refreshTokenService;

    @Override
    @Transactional
    public UserResponse createUser(CreateUserRequest request) {

        userRepository.findByEmployeeCode(request.getEmployeeCode())
                .ifPresent(user -> {
                    throw new DuplicateResourceException("Employee code already exists");
                });

        userRepository.findByEmail(request.getEmail())
                .ifPresent(user -> {
                    throw new DuplicateResourceException("Email already exists");
                });

        User user = new User();
        user.setEmployeeCode(request.getEmployeeCode());
        user.setFullName(request.getFullName());
        user.setEmail(request.getEmail());
        user.setDepartmentId(request.getDepartmentId());
        user.setDesignationId(request.getDesignationId());
        user.setRole(request.getRole());
        user.setActive(request.getActive());
        // Hashed with BCrypt before it ever reaches the database. The plaintext is not logged,
        // not stored and not present on UserResponse.
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        User savedUser = userRepository.save(user);

        return mapToResponse(savedUser);
    }

    /**
     * Reads one user, enforcing that a caller without {@link Permission#USER_READ} may only read their
     * own record.
     *
     * <p>Without this, any authenticated user could walk {@code /api/users/1..n} and rebuild the
     * directory that locking down {@code GET /api/users} was meant to protect - the collection endpoint
     * and the item endpoint have to be guarded together or neither is guarded.
     *
     * <p>Answers 404 rather than 403 for someone else's record, for the same
     * no-existence-disclosure reason as {@code TicketServiceImpl.getTicketById}.
     */
    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {

        if (!AuthenticatedCurrentUserProvider.currentUserHas(Permission.USER_READ)
                && !currentUserProvider.requireCurrentUserId().equals(id)) {
            throw ResourceNotFoundException.of("User", id);
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));

        return mapToResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    /**
     * One page of users, read through a projection.
     *
     * <p>The projection is not about N+1 - {@code User} has no associations. It is so that a directory
     * listing never selects {@code password_hash} from the database; see {@code UserRow}.
     */
    public Page<UserResponse> searchUsers(Pageable pageable) {
        return userRepository.findUserRows(pageable).map(UserServiceImpl::mapRow);
    }

    /**
     * Replaces the calling user's own password (see {@link UserService#changeOwnPassword}).
     *
     * <p>Four properties worth stating, because each is a way this endpoint could have gone wrong:
     *
     * <ul>
     *   <li><strong>No id parameter.</strong> The target is always
     *       {@code currentUserProvider.requireCurrentUserId()}. An endpoint that took a user id would
     *       need its own authorization rule, and the version of that rule that is wrong is the one
     *       that lets a caller set somebody else's password.
     *   <li><strong>The current password is verified.</strong> Without it a stolen or leaked access
     *       token would be enough to lock the real owner out permanently - the token expires, a
     *       changed password does not.
     *   <li><strong>An account with no password set cannot use this.</strong> A {@code NULL} hash
     *       means "cannot authenticate" (V13), and treating it as "no current password required"
     *       would turn every pre-existing passwordless account into one any token holder could claim.
     *       It fails the same way a wrong password does.
     *   <li><strong>The plaintext is hashed here and nowhere stored or logged.</strong> Only the user
     *       id reaches the log.
     * </ul>
     *
     * <p><strong>Phase 7 closed the limitation this method used to carry.</strong> It previously noted
     * that tokens already issued stayed valid until they expired, because there was no revocation
     * mechanism. There is one now, and a password change uses it: <em>every</em> session ends,
     * including the one making the call. That is the stricter of the two defensible options - the
     * alternative, keeping the current session alive, is friendlier but would mean a password changed
     * in response to a suspected compromise leaves the attacker's session running if they are the one
     * holding the current token.
     *
     * <p>The practical effect for a client is one 401 after a successful change, then a normal login
     * with the new password. The 204 contract is unchanged.
     */
    @Override
    @Transactional
    public void changeOwnPassword(ChangePasswordRequest request) {
        Long userId = currentUserProvider.requireCurrentUserId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        String storedHash = user.getPasswordHash();
        if (storedHash == null || storedHash.isBlank()) {
            log.info("Password change rejected for user {}: no password is set", userId);
            throw new InvalidCredentialsException("The current password is incorrect");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), storedHash)) {
            log.info("Password change rejected for user {}: current password did not match", userId);
            throw new InvalidCredentialsException("The current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        refreshTokenService.revokeAllSessions(user, RevocationReason.PASSWORD_CHANGED);
        log.info("Password changed for user {}; every session was ended", userId);
    }

    /**
     * An administrator sets someone else's password.
     *
     * <p>The account-recovery path. Without it, a user who forgets their password has no way back -
     * {@code changeOwnPassword} needs the old password and there is no email-based reset flow - and
     * the only remedy would be editing {@code password_hash} by hand in the database.
     *
     * <p>Every session ends, for the obvious reason: a reset is usually performed <em>because</em>
     * something is wrong with the account, and leaving the existing sessions running would defeat it.
     *
     * <p><strong>Deliberately not included: a forced change on next sign-in.</strong> That needs a
     * "must change password" flag, a 403-with-a-reason on every other endpoint while it is set, and a
     * change endpoint that works without an ordinary session - a feature, not a detail, and it is
     * recorded as future work rather than half-built here.
     */
    @Override
    @Transactional
    public void resetPassword(Long userId, ResetPasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        int revoked = refreshTokenService.revokeAllSessions(user, RevocationReason.PASSWORD_RESET);
        log.info("Administrator {} reset the password for user {}; {} session(s) ended",
                currentUserProvider.requireCurrentUserId(), userId, revoked);
    }

    /**
     * Activates or deactivates an account.
     *
     * <p>Until Phase 7 there was no way to do this through the API at all - {@code active} could only
     * be set when the user was created - so disabling a leaver meant a manual {@code UPDATE}. Worse,
     * that manual update did nothing to their live sessions: the login path refused them, but every
     * token already in their hands kept working for the rest of its lifetime.
     *
     * <p>Two guards, each protecting against a different way of locking the organization out:
     *
     * <ul>
     *   <li><strong>You cannot deactivate yourself.</strong> Always a mistake, and immediately
     *       self-inflicted: the next request fails and the account can no longer be re-enabled by the
     *       person who disabled it.</li>
     *   <li><strong>You cannot deactivate the last active administrator.</strong> Nobody would be left
     *       who can manage users, and recovery would mean editing the database. This is the same
     *       invariant {@code BootstrapAdminInitializer} exists to establish, enforced at the other
     *       end.</li>
     * </ul>
     *
     * <p>Reactivating is not treated as a security event and revokes nothing: there is nothing to
     * revoke, since the account could not have been issued a token while it was inactive.
     */
    @Override
    @Transactional
    public UserResponse updateStatus(Long userId, UpdateUserStatusRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        boolean active = Boolean.TRUE.equals(request.getActive());
        if (!active) {
            rejectSelfTargeting(userId, "deactivate your own account");
            rejectRemovingLastAdministrator(user, "Deactivating");
        }

        if (Boolean.TRUE.equals(user.getActive()) == active) {
            log.info("User {} is already {}; nothing changed", userId, active ? "active" : "inactive");
            return mapToResponse(user);
        }

        user.setActive(active);
        if (!active) {
            int revoked = refreshTokenService.revokeAllSessions(
                    user, RevocationReason.ACCOUNT_DEACTIVATED);
            log.info("Deactivated user {}; {} session(s) ended and outstanding access tokens "
                    + "invalidated", userId, revoked);
        } else {
            log.info("Reactivated user {}", userId);
        }
        return mapToResponse(user);
    }

    /**
     * Changes a user's role, and with it their permissions.
     *
     * <p>Ending every session is the point, not a side effect. Authorities are derived from the
     * {@code role} claim inside the access token, so a token minted before the change still carries
     * the old role - and {@code @PreAuthorize} would keep honouring it until the token expired. For a
     * promotion that is merely a delay; for a demotion, which is the case that matters, it would mean
     * the permissions being removed stay usable for up to the full token lifetime.
     *
     * <p>Two guards again, mirroring {@link #updateStatus}:
     *
     * <ul>
     *   <li><strong>You cannot change your own role.</strong> Blocks the obvious self-escalation -
     *       anyone with {@code USER_MANAGE} promoting themselves - and the equally awkward
     *       self-demotion that strips the permission needed to undo it.</li>
     *   <li><strong>You cannot demote the last active administrator.</strong> Same invariant as
     *       deactivation: somebody must be able to manage users.</li>
     * </ul>
     */
    @Override
    @Transactional
    public UserResponse updateRole(Long userId, UpdateUserRoleRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        rejectSelfTargeting(userId, "change your own role");

        if (user.getRole() == request.getRole()) {
            log.info("User {} already holds role {}; nothing changed", userId, request.getRole());
            return mapToResponse(user);
        }
        if (user.getRole() == Role.ADMIN) {
            rejectRemovingLastAdministrator(user, "Changing the role of");
        }

        Role previous = user.getRole();
        user.setRole(request.getRole());
        int revoked = refreshTokenService.revokeAllSessions(user, RevocationReason.ROLE_CHANGED);
        log.info("Changed user {} from role {} to {}; {} session(s) ended so the new authorities "
                + "take effect immediately", userId, previous, request.getRole(), revoked);
        return mapToResponse(user);
    }

    /**
     * Force-signs-out a user without otherwise touching the account.
     *
     * <p>The proportionate response to "a laptop was lost" or "that session looks wrong": the user
     * keeps their password and their role and simply signs in again. Resetting the password would
     * achieve the same revocation, but would also lock out a user who has done nothing wrong.
     *
     * <p>Self-targeting is allowed here, unlike the other two: signing yourself out of everything is
     * exactly what {@code POST /api/auth/logout-all} does, and there is nothing to recover from.
     */
    @Override
    @Transactional
    public int revokeSessions(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        int revoked = refreshTokenService.revokeAllSessions(user, RevocationReason.ADMIN_REVOKED);
        log.info("Administrator {} ended every session for user {}: {} refresh token(s) revoked",
                currentUserProvider.requireCurrentUserId(), userId, revoked);
        return revoked;
    }

    private void rejectSelfTargeting(Long targetUserId, String what) {
        if (targetUserId.equals(currentUserProvider.requireCurrentUserId())) {
            throw new InvalidReferenceException("You cannot " + what
                    + ". Ask another administrator to do it.");
        }
    }

    /**
     * Guards the invariant that at least one active administrator exists.
     *
     * <p>Counted rather than assumed, and counted over <em>active</em> administrators specifically: a
     * deactivated ADMIN row cannot sign in, so it does not keep the organization recoverable.
     */
    private void rejectRemovingLastAdministrator(User user, String action) {
        if (user.getRole() != Role.ADMIN || !Boolean.TRUE.equals(user.getActive())) {
            return;
        }
        if (userRepository.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            throw new InvalidReferenceException(action + " the last active administrator would leave "
                    + "nobody able to manage users. Appoint another administrator first.");
        }
    }

    /** Maps a read projection to the response DTO. Field names and types are unchanged. */
    private static UserResponse mapRow(com.forward.it_software_support_portal.repository.projection.UserRow row) {
        return UserResponse.builder()
                .id(row.id())
                .employeeCode(row.employeeCode())
                .fullName(row.fullName())
                .email(row.email())
                .departmentId(row.departmentId())
                .designationId(row.designationId())
                .role(row.role().name())
                .active(row.active())
                .build();
    }

    /** Entity-based mapping, still used by create and single-user read. */
    private UserResponse mapToResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .employeeCode(user.getEmployeeCode())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .departmentId(user.getDepartmentId())
                .designationId(user.getDesignationId())
                .role(user.getRole().name())
                .active(user.getActive())
                .build();
    }
}
