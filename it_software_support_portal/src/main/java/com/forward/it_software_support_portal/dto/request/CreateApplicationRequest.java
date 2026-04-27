package com.forward.it_software_support_portal.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateApplicationRequest {

    @NotBlank(message = "Application name is required")
    private String appName;

    @NotBlank(message = "Module name is required")
    private String moduleName;

    @NotNull(message = "Active Status")
    private Boolean active;

//    private String description;
}
