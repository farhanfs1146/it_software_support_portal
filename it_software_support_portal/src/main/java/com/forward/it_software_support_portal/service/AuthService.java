package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.dto.request.RefreshTokenRequest;
import com.forward.it_software_support_portal.dto.response.CurrentUserResponse;
import com.forward.it_software_support_portal.dto.response.LoginResponse;

/**
 * Authentication and session lifecycle.
 *
 * <p>The three credential-presenting operations ({@code login}, {@code refresh}, {@code logout}) all
 * take their credential from the request rather than the security context, because in each case the
 * caller has no access token yet - or no longer has a usable one. The two that act on "the current
 * user" ({@code logoutAll}, {@code currentUser}) read the authenticated principal instead.
 */
public interface AuthService {

    LoginResponse login(LoginRequest request);

    /**
     * Exchanges a refresh token for a new access token and a replacement refresh token.
     *
     * <p>Rotating on every refresh is what bounds the value of a stolen refresh token; see
     * {@code PersistentRefreshTokenService}. Returns 401 for any failure, with no indication of which.
     */
    LoginResponse refresh(RefreshTokenRequest request);

    /** Ends the one session the presented refresh token belongs to. Idempotent and always succeeds. */
    void logout(RefreshTokenRequest request);

    /**
     * Ends every session the authenticated user holds, including the one making the call, and
     * invalidates their outstanding access tokens immediately.
     */
    void logoutAll();

    /** The authenticated user's identity and effective permissions. */
    CurrentUserResponse currentUser();
}
