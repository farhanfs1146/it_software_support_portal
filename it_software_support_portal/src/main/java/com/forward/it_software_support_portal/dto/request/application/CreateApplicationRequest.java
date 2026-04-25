package com.forward.it_software_support_portal.dto.request.application;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateApplicationRequest {

    @NotBlank(message = "Application name is required")
    private String appName;

    @NotBlank(message = "Module name is required")
    private String moduleName;

//    private String description;
}
