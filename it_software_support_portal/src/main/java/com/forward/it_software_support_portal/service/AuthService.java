package com.forward.it_software_support_portal.service;

import com.forward.it_software_support_portal.dto.request.LoginRequest;
import com.forward.it_software_support_portal.dto.response.LoginResponse;

public interface AuthService {

    /**
     * Authenticates an email/password pair and issues an access token.
     *
     * @throws com.forward.it_software_support_portal.security.InvalidCredentialsException if the
     *         credentials do not match, the account has no password set, or the account is inactive
     */
    LoginResponse login(LoginRequest request);
}
