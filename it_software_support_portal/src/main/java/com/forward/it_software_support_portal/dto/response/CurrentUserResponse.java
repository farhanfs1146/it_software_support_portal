package com.forward.it_software_support_portal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Who the caller is and what they may do - the answer to {@code GET /api/auth/me}.
 *
 * <h2>Why this endpoint exists</h2>
 *
 * Without it a client has only two ways to find out what it is allowed to do, and both are bad. It can
 * decode the access token itself, which means every client reimplements this application's claim names
 * and its role-to-permission mapping, and they drift the moment the mapping changes. Or it can render
 * every control and let the server return 403s, which is a worse experience and leaks the shape of the
 * permission model through failed requests.
 *
 * <p>{@code permissions} is the list the server will actually enforce for this role, produced from the
 * same {@code RolePermissions} table that {@code @PreAuthorize} consults. A UI can hide what the user
 * cannot do and be right by construction.
 *
 * <h2>This is a convenience, not a security boundary</h2>
 *
 * Nothing here authorizes anything. The server checks permissions on every request regardless of what a
 * client did with this response; hiding a button is presentation, not access control.
 *
 * @param permissions effective permission names, sorted so the response is stable and comparable
 * @param sessionCount how many live refresh tokens this account holds - that is, roughly how many
 *                     devices are signed in. Shown so a user can notice a session they do not recognise
 *                     and end it with "sign out everywhere".
 */
@Data
@Builder
public class CurrentUserResponse {

    @Schema(description = "The authenticated user's id")
    private Long id;

    @Schema(description = "Employee code")
    private Long employeeCode;

    @Schema(description = "Full name")
    private String fullName;

    @Schema(description = "Email address")
    private String email;

    @Schema(description = "Department id, if set")
    private Long departmentId;

    @Schema(description = "Designation id, if set")
    private Long designationId;

    @Schema(description = "Role name", example = "IT_SUPPORT")
    private String role;

    @Schema(description = "Effective permissions for this role, as the server enforces them")
    private List<String> permissions;

    @Schema(description = "Live sessions (refresh tokens) this account currently holds", example = "2")
    private long sessionCount;
}
