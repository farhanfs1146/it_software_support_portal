package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.CreateApplicationRequest;
import com.forward.it_software_support_portal.dto.response.ApplicationResponse;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApplicationService {

    ApplicationResponse createApplication(CreateApplicationRequest request);

    ApplicationResponse getApplicationById(Long id);

    /**
     * One page of applications.
     *
     * <p>Replaces the previous unbounded {@code getAllApplications()}. No unbounded variant remains.
     */
    Page<ApplicationResponse> searchApplications(Pageable pageable);

    /** One page of active applications only - the catalogue a ticket may be raised against. */
    Page<ApplicationResponse> searchActiveApplications(Pageable pageable);

    ApplicationResponse updateApplication(Long id, CreateApplicationRequest request);

    void deactivateApplication(Long id);
}
