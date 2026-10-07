package com.forward.it_software_support_portal.controller;

import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.dto.response.LoginResponse;
import com.forward.it_software_support_portal.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoint. The only endpoint in the application reachable without a token.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Authenticate and obtain an access token",
            description = "Returns a signed bearer token. Send it as 'Authorization: Bearer <token>' "
                    + "on every other endpoint. Returns 401 for any credential failure.")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
