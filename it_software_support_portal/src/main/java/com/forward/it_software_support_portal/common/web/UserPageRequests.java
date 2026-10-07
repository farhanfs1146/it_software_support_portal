package com.forward.it_software_support_portal.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * User-specific pagination rules (Phase 6).
 *
 * <p>Sortable properties are limited to the ones a directory is plausibly ordered by, and all of them are
 * columns on {@code users} - nothing here can produce a join or an expression sort.
 *
 * <p>The default is name order, because this endpoint exists so support staff can find a person, and
 * "alphabetical" is what a human expects of a directory. {@code id} is appended as a tiebreaker, since
 * names are not unique and offset pagination needs a total order to avoid skipping or repeating rows.
 */
public final class UserPageRequests {

    private static final Set<String> SORTABLE = Set.of(
            "fullName", "email", "employeeCode", "role", "active", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("fullName"), Sort.Order.asc("id"));

    private UserPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
