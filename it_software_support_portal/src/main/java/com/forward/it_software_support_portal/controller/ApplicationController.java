package com.forward.it_software_support_portal.controller;

import com.forward.it_software_support_portal.dto.request.application.CreateApplicationRequest;
import com.forward.it_software_support_portal.dto.response.application.ApplicationResponse;
import com.forward.it_software_support_portal.service.ApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping
    public ApplicationResponse createApplication(
            @Valid @RequestBody CreateApplicationRequest request
    ) {
        return applicationService.createApplication(request);
    }

    @GetMapping("/{id}")
    public ApplicationResponse getById(@PathVariable Long id) {
        return applicationService.getApplicationById(id);
    }

    @GetMapping
    public List<ApplicationResponse> getAll() {
        return applicationService.getAllApplications();
    }

    @GetMapping("/active")
    public List<ApplicationResponse> getActive() {
        return applicationService.getActiveApplications();
    }

    @PutMapping("/{id}")
    public ApplicationResponse update(
            @PathVariable Long id,
            @Valid @RequestBody CreateApplicationRequest request
    ) {
        return applicationService.updateApplication(id, request);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable Long id) {
        applicationService.deactivateApplication(id);
    }
}
