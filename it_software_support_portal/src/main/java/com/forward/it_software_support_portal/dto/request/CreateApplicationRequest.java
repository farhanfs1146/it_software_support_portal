package com.forward.it_software_support_portal.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApplicationRequest {

    @NotBlank(message = "Application name is required")
    @Size(max = 150, message = "Application name must be at most 150 characters")
    private String appName;

    @NotBlank(message = "Module name is required")
    @Size(max = 100, message = "Module name must be at most 100 characters")
    private String moduleName;

    @NotNull(message = "Active Status")
    private Boolean active;

//    private String description;
}
