package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.dto.request.application.CreateApplicationRequest;
import com.forward.it_software_support_portal.dto.response.application.ApplicationResponse;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;

    @Override
    public ApplicationResponse createApplication(CreateApplicationRequest request) {

        Application app = new Application();
        app.setAppName(request.getAppName());
        app.setModuleName(request.getModuleName());
//        app.setDescription(request.getDescription());
        app.setActive(true);

        Application saved = applicationRepository.save(app);

        return mapToResponse(saved);
    }

    @Override
    public ApplicationResponse getApplicationById(Long id) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Application not found"));

        return mapToResponse(app);
    }

    @Override
    public List<ApplicationResponse> getAllApplications() {

        return applicationRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public List<ApplicationResponse> getActiveApplications() {

        return applicationRepository.findByActiveTrue()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public ApplicationResponse updateApplication(Long id, CreateApplicationRequest request) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Application not found"));

        app.setAppName(request.getAppName());
        app.setModuleName(request.getModuleName());
//        app.setDescription(request.getDescription());

        Application updated = applicationRepository.save(app);

        return mapToResponse(updated);
    }

    @Override
    public void deactivateApplication(Long id) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Application not found"));

        app.setActive(false);
        applicationRepository.save(app);
    }

    private ApplicationResponse mapToResponse(Application app) {

        return ApplicationResponse.builder()
                .id(app.getId())
                .appName(app.getAppName())
                .moduleName(app.getModuleName())
//                .description(app.getDescription())
                .active(app.isActive())
                .build();
    }
}
