package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotNull(message = "Employee code is required")
    @Schema(description = "Employee unique code like card numbers or cnic etc.", example = "13411")
    private Long employeeCode;

    @NotBlank(message = "Full name is required")
    @Schema(description = "Full name", example = "Farhan Ali")
    private String fullName;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @Schema(description = "Employee department id which will refer as foreign key", example = "1")
    private Long departmentId;

    @Schema(description = "Employee designation id which will refer as foreign key", example = "1")
    private Long designationId;

    @NotNull(message = "Role is required")
    private Role role;

    @NotNull(message = "Active Status")
    @Schema(description = "whether employee will consider as active or not", example = "true")
    private Boolean active;
}