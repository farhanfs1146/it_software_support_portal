package com.forward.it_software_support_portal.security;

import com.forward.it_software_support_portal.enums.Role;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps the roles this project already defines onto {@link Permission}s.
 *
 * <p><strong>The roles were not invented here.</strong> {@code Role} already contained
 * {@code EMPLOYEE, MANAGER, HOD, DIRECTOR, IT_SUPPORT, DEVELOPER, ADMIN} before Phase 4. They were
 * stored on every user and echoed back by the API, but never used for a single decision. This class is
 * where they finally mean something, and no new role was added.
 *
 * <p><strong>The grouping is derived from the role names and kept as small as possible:</strong>
 *
 * <ul>
 *   <li><strong>Requesters</strong> - {@code EMPLOYEE, MANAGER, HOD, DIRECTOR}. People who raise
 *       tickets. They may create tickets, read the ones they are involved in, and read the application
 *       catalogue so they can file against it.
 *   <li><strong>Support staff</strong> - {@code IT_SUPPORT, DEVELOPER}. The IT side. They additionally
 *       read every ticket, change status and assign, and read users in order to pick an assignee.
 *   <li><strong>Administrators</strong> - {@code ADMIN}. Support staff plus user and application
 *       management.
 * </ul>
 *
 * <p><strong>What is deliberately NOT granted, and why.</strong> {@code MANAGER}, {@code HOD} and
 * {@code DIRECTOR} get exactly the same permissions as {@code EMPLOYEE}. It is tempting to assume a
 * manager should see their department's tickets, but nothing in this codebase defines that:
 * {@code users.department_id} is a bare {@code BIGINT} pointing at a table that does not exist, so
 * there is no department to scope by and no way to test such a rule. Granting broad visibility on the
 * strength of a role's name would be inventing a business rule and would hand three roles the ability
 * to read every ticket in the system. Until departments exist and the rule is decided, these roles
 * fail closed. See docs/SECURITY.md, "Pending business decisions".
 *
 * <p>Equally, {@code DEVELOPER} is treated the same as {@code IT_SUPPORT}: both are IT-side roles that
 * work tickets. Whether a developer should be able to <em>assign</em> as well as work is a distinction
 * this codebase does not make, so none is introduced.
 */
public final class RolePermissions {

    private static final Set<Permission> REQUESTER = EnumSet.of(
            Permission.TICKET_CREATE,
            Permission.TICKET_READ_OWN,
            Permission.APPLICATION_READ);

    private static final Set<Permission> SUPPORT_STAFF = EnumSet.of(
            Permission.TICKET_CREATE,
            Permission.TICKET_READ_OWN,
            Permission.TICKET_READ_ALL,
            Permission.TICKET_STATUS_CHANGE,
            Permission.TICKET_ASSIGN,
            Permission.USER_READ,
            Permission.APPLICATION_READ);

    private static final Set<Permission> ADMINISTRATOR = EnumSet.copyOf(SUPPORT_STAFF);

    static {
        ADMINISTRATOR.add(Permission.USER_MANAGE);
        ADMINISTRATOR.add(Permission.APPLICATION_MANAGE);
    }

    private static final Map<Role, Set<Permission>> BY_ROLE;

    static {
        Map<Role, Set<Permission>> map = new EnumMap<>(Role.class);
        map.put(Role.EMPLOYEE, REQUESTER);
        map.put(Role.MANAGER, REQUESTER);
        map.put(Role.HOD, REQUESTER);
        map.put(Role.DIRECTOR, REQUESTER);
        map.put(Role.IT_SUPPORT, SUPPORT_STAFF);
        map.put(Role.DEVELOPER, SUPPORT_STAFF);
        map.put(Role.ADMIN, ADMINISTRATOR);
        BY_ROLE = Collections.unmodifiableMap(map);
    }

    private RolePermissions() {
    }

    /**
     * @return the permissions for a role; an empty set for an unmapped role, so a role added to the
     *         enum without being mapped here grants nothing rather than everything
     */
    public static Set<Permission> of(Role role) {
        if (role == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(BY_ROLE.getOrDefault(role, Set.of()));
    }

    /** Authority names as Spring Security sees them. */
    public static Set<String> authorityNames(Role role) {
        return of(role).stream().map(Permission::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
