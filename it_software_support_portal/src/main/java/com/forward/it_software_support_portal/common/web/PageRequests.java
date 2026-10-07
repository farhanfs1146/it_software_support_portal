package com.forward.it_software_support_portal.common.web;

import com.forward.it_software_support_portal.common.exception.InvalidReferenceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;

import java.util.Set;

/**
 * Shared pagination mechanics for every collection endpoint.
 *
 * <p>Extracted from {@code TicketPageRequests} in Phase 6, when the user and application endpoints
 * needed the same rules. Only two things differ per resource — which properties may be sorted on, and
 * what the default order is — so those are parameters and everything else is shared. The ticket endpoint
 * now delegates here, and its existing pagination tests are what prove the extraction changed nothing.
 *
 * <p>Three protections are enforced here rather than trusted to the caller:
 *
 * <ul>
 *   <li><strong>A maximum page size.</strong> Without it an endpoint is still unbounded: a client could
 *       ask for {@code size=1000000} and reproduce the original defect. Oversized requests are clamped
 *       rather than rejected, so a client asking for too much gets data instead of an error.
 *   <li><strong>A sort whitelist.</strong> An arbitrary property would at best produce an unindexed sort
 *       and at worst fail at query time.
 *   <li><strong>A deterministic tiebreaker.</strong> {@code id} is always appended. Sorting by a
 *       non-unique column alone is not deterministic, and non-deterministic ordering makes offset
 *       pagination skip and repeat rows between pages.
 * </ul>
 */
public final class PageRequests {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    /**
     * @param page        zero-based page index; negative is treated as 0
     * @param size        page size; clamped to {@value #MAX_PAGE_SIZE}, defaulting to
     *                    {@value #DEFAULT_PAGE_SIZE} when not positive
     * @param sort        {@code property} or {@code property,(asc|desc)}; blank uses {@code defaultSort}
     * @param sortable    the properties this resource permits sorting on
     * @param defaultSort the order applied when the caller asks for none
     */
    public static Pageable of(Integer page, Integer size, String sort,
                              Set<String> sortable, Sort defaultSort) {
        int pageIndex = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(pageIndex, pageSize, parseSort(sort, sortable, defaultSort));
    }

    private static Sort parseSort(String sort, Set<String> sortable, Sort defaultSort) {
        if (sort == null || sort.isBlank()) {
            return defaultSort;
        }

        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!sortable.contains(property)) {
            throw new InvalidReferenceException(
                    "sort property '" + property + "' is not sortable. Allowed: " + sortable);
        }

        boolean descending = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
        Sort.Order primary = descending ? Sort.Order.desc(property) : Sort.Order.asc(property);

        // Always break ties on id so paging is stable.
        return "id".equals(property)
                ? Sort.by(primary)
                : Sort.by(primary, descending ? Sort.Order.desc("id") : Sort.Order.asc("id"));
    }

    /**
     * Pagination metadata as response headers.
     *
     * <p>Headers rather than a wrapper object, so a response body stays the JSON array it has always
     * been and existing clients keep parsing it. The same choice was made for tickets in Phase 3; see
     * docs/PERFORMANCE.md for the compatibility discussion.
     */
    public static HttpHeaders headers(Page<?> page) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Total-Count", String.valueOf(page.getTotalElements()));
        headers.add("X-Total-Pages", String.valueOf(page.getTotalPages()));
        headers.add("X-Page-Number", String.valueOf(page.getNumber()));
        headers.add("X-Page-Size", String.valueOf(page.getSize()));
        headers.add("X-Has-Next", String.valueOf(page.hasNext()));
        return headers;
    }
}
