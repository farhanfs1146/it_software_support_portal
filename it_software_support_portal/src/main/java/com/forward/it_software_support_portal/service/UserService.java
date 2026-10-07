package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.ChangePasswordRequest;
import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.request.ResetPasswordRequest;
import com.forward.it_software_support_portal.dto.request.UpdateUserRoleRequest;
import com.forward.it_software_support_portal.dto.request.UpdateUserStatusRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    UserResponse createUser(CreateUserRequest request);

    UserResponse getUserById(Long id);

    /**
     * One page of users.
     *
     * <p>Replaces the previous {@code getAllUsers()}, which returned every row with no upper bound - the
     * audit measured a 787 KB response for 5,103 users. Phase 4 made the endpoint require
     * {@code USER_READ}, so it is no longer an anonymous disclosure, but it was still unbounded. There is
     * deliberately no unbounded variant any more.
     */
    Page<UserResponse> searchUsers(Pageable pageable);

    /**
     * Replaces the calling user's own password.
     *
     * <p>Closes a contradiction rather than adding a feature. {@code BootstrapAdminInitializer} logs
     * "change this password after first sign-in", and docs/SECURITY.md says the same - but there was
     * no endpoint through which anyone could, so the only administrator a fresh deployment has was
     * permanently stuck on a password that had been typed into a deployment script.
     *
     * <p>The caller is always the authenticated user; there is no id parameter, so this cannot be used
     * to set somebody else's password.
     *
     * @throws com.forward.it_software_support_portal.security.InvalidCredentialsException if
     *         {@code currentPassword} does not match, or the account has no password set
     */
    /**
     * Replaces the caller's own password, after verifying the one being replaced.
     *
     * <p>Since Phase 7 this also ends every session the user holds - including the one making the
     * call. A password change that left old sessions alive would make it useless as a response to a
     * suspected compromise, which is the main reason people change passwords in a hurry.
     */
    void changeOwnPassword(ChangePasswordRequest request);

    /**
     * Sets another user's password, without knowing the old one. Requires {@code USER_MANAGE}, and
     * ends every session that account holds.
     */
    void resetPassword(Long userId, ResetPasswordRequest request);

    /**
     * Activates or deactivates an account. Deactivation ends every session it holds and takes effect
     * on the next request, not when the access token happens to expire.
     */
    UserResponse updateStatus(Long userId, UpdateUserStatusRequest request);

    /**
     * Changes a user's role. Ends every session, because authorities travel in the access token and a
     * demotion that waits for the token to expire is not a demotion.
     */
    UserResponse updateRole(Long userId, UpdateUserRoleRequest request);

    /**
     * Force-signs-out a user without otherwise changing their account.
     *
     * @return how many sessions were ended
     */
    int revokeSessions(Long userId);
}
