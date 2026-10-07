package com.forward.it_software_support_portal.controller;

import com.forward.it_software_support_portal.common.web.ApplicationPageRequests;
import com.forward.it_software_support_portal.common.web.PageRequests;
import com.forward.it_software_support_portal.dto.request.CreateApplicationRequest;
import com.forward.it_software_support_portal.dto.response.ApplicationResponse;
import com.forward.it_software_support_portal.service.ApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The application/module catalogue.
 *
 * <p>Reading it is open to any authenticated user, because raising a ticket means choosing the
 * application it concerns. Changing the catalogue is administrative.
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @PreAuthorize("hasAuthority('APPLICATION_MANAGE')")
    @PostMapping
    public ApplicationResponse createApplication(
            @Valid @RequestBody CreateApplicationRequest request
    ) {
        return applicationService.createApplication(request);
    }

    @PreAuthorize("hasAuthority('APPLICATION_READ')")
    @GetMapping("/{id}")
    public ApplicationResponse getById(@PathVariable Long id) {
        return applicationService.getApplicationById(id);
    }

    /**
     * One page of the catalogue, name-ordered by default.
     *
     * <p>Bounded in Phase 6. Body shape unchanged - still a JSON array - with pagination metadata in
     * headers, matching tickets and users.
     */
    @PreAuthorize("hasAuthority('APPLICATION_READ')")
    @GetMapping
    public ResponseEntity<List<ApplicationResponse>> getAll(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        Page<ApplicationResponse> result =
                applicationService.searchApplications(ApplicationPageRequests.of(page, size, sort));
        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    /** One page of active applications only. Bounded in Phase 6 for the same reason as the full list. */
    @PreAuthorize("hasAuthority('APPLICATION_READ')")
    @GetMapping("/active")
    public ResponseEntity<List<ApplicationResponse>> getActive(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort
    ) {
        Page<ApplicationResponse> result =
                applicationService.searchActiveApplications(ApplicationPageRequests.of(page, size, sort));
        return ResponseEntity.ok()
                .headers(PageRequests.headers(result))
                .body(result.getContent());
    }

    @PreAuthorize("hasAuthority('APPLICATION_MANAGE')")
    @PutMapping("/{id}")
    public ApplicationResponse update(
            @PathVariable Long id,
            @Valid @RequestBody CreateApplicationRequest request
    ) {
        return applicationService.updateApplication(id, request);
    }

    @PreAuthorize("hasAuthority('APPLICATION_MANAGE')")
    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable Long id) {
        applicationService.deactivateApplication(id);
    }
}
