package com.forward.it_software_support_portal.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 100, message = "Email must be at most 100 characters")
    @Schema(description = "The user's email address", example = "admin@example.com")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(max = 200, message = "Password must be at most 200 characters")
    @Schema(description = "The user's password")
    private String password;
}
