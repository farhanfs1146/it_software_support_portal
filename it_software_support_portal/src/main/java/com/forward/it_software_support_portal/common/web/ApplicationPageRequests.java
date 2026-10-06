package com.forward.it_software_support_portal.common.web;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Application-catalogue pagination rules (Phase 6).
 *
 * <p>Defaults to name order: the catalogue is browsed to find an application to raise a ticket against,
 * so alphabetical is the useful order. {@code id} is appended as a tiebreaker because application names
 * are not unique in the schema.
 */
public final class ApplicationPageRequests {

    private static final Set<String> SORTABLE = Set.of("appName", "moduleName", "active", "id");

    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("appName"), Sort.Order.asc("id"));

    private ApplicationPageRequests() {
    }

    public static Pageable of(Integer page, Integer size, String sort) {
        return PageRequests.of(page, size, sort, SORTABLE, DEFAULT_SORT);
    }
}
