package com.forward.it_software_support_portal.service.impl;

import com.forward.it_software_support_portal.common.exception.ResourceNotFoundException;
import com.forward.it_software_support_portal.dto.request.CreateApplicationRequest;
import com.forward.it_software_support_portal.dto.response.ApplicationResponse;
import com.forward.it_software_support_portal.entity.Application;
import com.forward.it_software_support_portal.repository.ApplicationRepository;
import com.forward.it_software_support_portal.service.ApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ApplicationServiceImpl implements ApplicationService {

    private final ApplicationRepository applicationRepository;

    @Override
    @Transactional
    public ApplicationResponse createApplication(CreateApplicationRequest request) {

        Application app = new Application();
        app.setAppName(request.getAppName());
        app.setModuleName(request.getModuleName());
//        app.setDescription(request.getDescription());
        app.setActive(request.getActive());

        Application saved = applicationRepository.save(app);

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ApplicationResponse getApplicationById(Long id) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));

        return mapToResponse(app);
    }

    @Override
    @Transactional(readOnly = true)
    /**
     * One page of applications.
     *
     * <p>No projection here, deliberately: {@code Application} has no associations, and its four columns
     * are exactly the four the response carries, so loading the entity already reads nothing spare. A
     * projection would add a type for no measurable benefit. Contrast {@code UserRow}, which exists
     * specifically so a listing never reads password hashes.
     */
    public Page<ApplicationResponse> searchApplications(Pageable pageable) {
        return applicationRepository.findAll(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApplicationResponse> searchActiveApplications(Pageable pageable) {
        return applicationRepository.findByActiveTrue(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional
    public ApplicationResponse updateApplication(Long id, CreateApplicationRequest request) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));

        app.setAppName(request.getAppName());
        app.setModuleName(request.getModuleName());
        app.setActive(request.getActive());
//        app.setDescription(request.getDescription());

        Application updated = applicationRepository.save(app);

        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public void deactivateApplication(Long id) {

        Application app = applicationRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", id));

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
