package com.forward.it_software_support_portal.service.impl;


import com.forward.it_software_support_portal.common.exception.DuplicateResourceException;
import com.forward.it_software_support_portal.common.identity.CurrentUserProvider;
import com.forward.it_software_support_portal.security.AuthenticatedCurrentUserProvider;
import com.forward.it_software_support_portal.security.Permission;
import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.dto.request.ChangePasswordRequest;
import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import com.forward.it_software_support_portal.entity.User;
import com.forward.it_software_support_portal.repository.UserRepository;
import com.forward.it_software_support_portal.security.InvalidCredentialsException;
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
     * <p><strong>Known limitation, consistent with the rest of the design.</strong> Access tokens
     * already issued stay valid until they expire - this application has no revocation mechanism and
     * docs/SECURITY.md records that as a deliberate trade-off of stateless JWTs. So a password change
     * stops new tokens being minted with the old password, which is what the bootstrap-admin case
     * needs, but it does not cut off a session someone already holds.
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
        log.info("Password changed for user {}", userId);
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
