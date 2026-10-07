package com.forward.it_software_support_portal.controller;


import com.forward.it_software_support_portal.common.web.PageRequests;
import com.forward.it_software_support_portal.common.web.UserPageRequests;
import com.forward.it_software_support_portal.dto.request.ChangePasswordRequest;
import com.forward.it_software_support_portal.dto.request.CreateUserRequest;
import com.forward.it_software_support_portal.dto.response.UserResponse;
import com.forward.it_software_support_portal.service.UserService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User administration.
 *
 * <p>Before Phase 4 every endpoint here was anonymous, and {@code GET /api/users} returned every
 * user's full name and email address to any caller - the audit's most direct data-exposure finding.
 * Access now requires an authenticated caller with the appropriate permission.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** Creating accounts, assigning roles and setting initial passwords is administrative. */
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @PostMapping
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        return userService.createUser(request);
    }

    /**
     * Readable by support staff - who need it to choose an assignee - and by a user looking at their
     * own record. The "or own record" part is enforced in the service, which knows the caller's id.
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public UserResponse getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    /**
     * A user replaces their own password.
     *
     * <p>Only {@code isAuthenticated()} is required, and no permission: changing your own password is
     * not an administrative act, and gating it behind one would leave exactly the account that most
     * needs it - the bootstrap administrator, created from a password in a deployment script - unable
     * to use it.
     *
     * <p>No {@code {id}} in the path on purpose. The account is taken from the token, so this endpoint
     * cannot be aimed at anyone else. Setting another user's password is a different capability with a
     * different rule, and it is deliberately not bundled in here.
     *
     * <p>Answers <strong>204</strong>, not 200 with an empty body: nothing is returned, and a password
     * change has no representation to return. (Audit P2-9 notes the older endpoints are loose about
     * this; a new one is no reason to repeat it.)
     */
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PatchMapping("/me/password")
    public void changeOwnPassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(request);
    }

    /**
     * One page of users, newest-name-first by default.
     *
     * <p>Listing exposes personal data, so it requires the dedicated read permission. Phase 6 bounded it:
     * the body is still a JSON array of {@code UserResponse}, but it is now a page rather than every row,
     * and pagination metadata travels in headers ({@code X-Total-Count}, {@code X-Total-Pages},
     * {@code X-Page-Number}, {@code X-Page-Size}, {@code X-Has-Next}) - the same convention tickets have
     * used since Phase 3.
     */
    @PreAuthorize("hasAuthority('USER_READ')")
    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers(
            @Parameter(description = "Zero-based page index")
            @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size; capped at 100")
            @RequestParam(required = false) Integer size,
            @Parameter(description = "Sort as 'property' or 'property,asc|desc'. "
                    + "Allowed: fullName, email, employeeCode, role, active, id")
            @RequestParam(required = false) String sort
    ) {
        Page<UserResponse> result = userService.searchUsers(UserPageRequests.of(page, size, sort));
        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }
}
