package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.application.CreateApplicationRequest;
import com.forward.it_software_support_portal.dto.response.application.ApplicationResponse;

import java.util.List;

public interface ApplicationService {

    ApplicationResponse createApplication(CreateApplicationRequest request);

    ApplicationResponse getApplicationById(Long id);

    List<ApplicationResponse> getAllApplications();

    List<ApplicationResponse> getActiveApplications();

    ApplicationResponse updateApplication(Long id, CreateApplicationRequest request);

    void deactivateApplication(Long id);
}
