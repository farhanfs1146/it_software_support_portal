package com.forward.it_software_support_portal.security;

/**
 * The capabilities this application actually has, expressed as granted authorities.
 *
 * <p><strong>Why permissions rather than roles in the checks.</strong> Endpoints are guarded by
 * permission (`hasAuthority("TICKET_ASSIGN")`) instead of by role (`hasRole("IT_SUPPORT")`). The role
 * stays the thing stored against a user; the permission is what the code asserts. That means changing
 * which roles may assign a ticket is an edit to {@link RolePermissions} rather than a hunt through
 * controllers, and a new role can be introduced without touching a single authorization expression.
 *
 * <p><strong>Why this list and no more.</strong> One permission per capability the API already
 * exposes, and these cover every endpoint it has. (This sentence used to assert an endpoint count,
 * which was already wrong and went further out of date the moment an endpoint was added - a number
 * nothing verifies is a comment that rots.) No speculative permissions for
 * features that do not exist (comments, attachments, SLA, reporting), because an unused permission is
 * an untested permission.
 */
public enum Permission {

    /** Raise a ticket. Every authenticated user can do this - it is the point of a support portal. */
    TICKET_CREATE,

    /**
     * Read tickets the user is involved in, as raiser or as assignee.
     *
     * <p>This is the floor: without it a user could not see the ticket they just created. Enforcing
     * "involved in" is a resource-level check, not something an endpoint annotation can express.
     */
    TICKET_READ_OWN,

    /**
     * Read any ticket regardless of involvement.
     *
     * <p>Support staff need this to triage. It is also what distinguishes a filtered list from an
     * unfiltered one: a caller without this permission has an ownership predicate forced into their
     * ticket queries.
     */
    TICKET_READ_ALL,

    /** Change a ticket's status. */
    TICKET_STATUS_CHANGE,

    /** Assign or reassign a ticket to a user. */
    TICKET_ASSIGN,

    /**
     * List and read user records.
     *
     * <p>Needed to choose an assignee. Before Phase 4 {@code GET /api/users} was anonymous and
     * returned every user's name and email address.
     */
    USER_READ,

    /** Create user accounts, including setting their initial password and role. */
    USER_MANAGE,

    /** Read the application/module catalogue. Needed to raise a ticket against an application. */
    APPLICATION_READ,

    /** Create, update or deactivate applications. */
    APPLICATION_MANAGE
}
