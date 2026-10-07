package com.forward.it_software_support_portal.dto.request;

import com.forward.it_software_support_portal.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotNull(message = "Employee code is required")
    @Schema(description = "Employee unique code like card numbers or cnic etc.", example = "13411")
    private Long employeeCode;

    @NotBlank(message = "Full name is required")
    @Size(max = 150, message = "Full name must be at most 150 characters")
    @Schema(description = "Full name", example = "Farhan Ali")
    private String fullName;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    @Size(max = 100, message = "Email must be at most 100 characters")
    private String email;

    @Schema(description = "Employee department id which will refer as foreign key", example = "1")
    private Long departmentId;

    @Schema(description = "Employee designation id which will refer as foreign key", example = "1")
    private Long designationId;

    @NotBlank(message = "An initial password is required")
    @Size(min = 12, max = 200, message = "Password must be between 12 and 200 characters")
    @Schema(description = "Initial password. Stored only as a BCrypt hash and never returned.",
            accessMode = Schema.AccessMode.WRITE_ONLY)
    private String password;

    @NotNull(message = "Role is required")
    private Role role;

    @NotNull(message = "Active Status")
    @Schema(description = "whether employee will consider as active or not", example = "true")
    private Boolean active;
}