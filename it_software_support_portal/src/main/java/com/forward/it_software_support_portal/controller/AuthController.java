package com.forward.it_software_support_portal.controller;

import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.dto.request.RefreshTokenRequest;
import com.forward.it_software_support_portal.dto.response.CurrentUserResponse;
import com.forward.it_software_support_portal.dto.response.LoginResponse;
import com.forward.it_software_support_portal.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The session lifecycle, end to end: sign in, stay signed in, sign out, and find out who you are.
 *
 * <p>{@code /login}, {@code /refresh} and {@code /logout} are reachable without an access token,
 * because in each case the caller does not have a usable one - that is the point of refresh. They are
 * not unauthenticated: each carries its own credential in the body and verifies it. {@code /logout-all}
 * and {@code /me} act on "the current user", which only an access token can establish, so both require
 * one.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Authenticate and start a session",
            description = "Returns a short-lived access token plus a refresh token. Send the access "
                    + "token as 'Authorization: Bearer <token>' on every other endpoint, and exchange "
                    + "the refresh token at POST /api/auth/refresh when it expires. Returns 401 for "
                    + "any credential failure and 429 when login attempts are throttled.")
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Exchange a refresh token for a new token pair",
            description = "Returns the same shape as login: a new access token AND a new refresh "
                    + "token. The refresh token presented is consumed - replace your stored copy with "
                    + "the one returned. Presenting a token that has already been exchanged is treated "
                    + "as a replay and revokes every session in that chain. Returns 401 for any "
                    + "failure, with no indication of which.")
    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "End the session this refresh token belongs to",
            description = "Idempotent: always 204, whether or not the token was known. Reporting an "
                    + "unknown token would make this endpoint an oracle for guessed tokens. The access "
                    + "token already issued for this session stays valid until it expires - use "
                    + "POST /api/auth/logout-all to end it immediately.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout")
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }

    @Operation(summary = "End every session for the current user",
            description = "Revokes all refresh tokens AND invalidates every outstanding access token "
                    + "immediately, including the one making this call. This is the control to use "
                    + "after a suspected compromise.")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PostMapping("/logout-all")
    public void logoutAll() {
        authService.logoutAll();
    }

    @Operation(summary = "The authenticated user and their effective permissions",
            description = "Lets a client render only what the user may actually do, without decoding "
                    + "the token or reimplementing the role-to-permission mapping. Advisory only: the "
                    + "server enforces permissions on every request regardless.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return authService.currentUser();
    }
}
