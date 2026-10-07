package com.forward.it_software_support_portal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Emits <strong>401 Unauthorized</strong> as an RFC 7807 problem document when a request carries no
 * usable credentials.
 *
 * <p>Two things this gets right that the default does not. First the status code: Phase 2 returned 400
 * for a missing identity because no authentication scheme existed and advertising 401 would have
 * implied one. Now that authentication is real, 401 is correct and 403 is reserved for an authenticated
 * caller who simply lacks the permission.
 *
 * <p>Second, the response says nothing useful to an attacker. Whether the token was absent, malformed,
 * expired or signed with the wrong key all produce the same body; the distinction is logged at debug
 * level instead. No stack trace, no class name, no hint about the signing algorithm.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAuthenticationEntryPoint.class);

    private final SecurityProblemWriter writer;

    public ProblemDetailAuthenticationEntryPoint(SecurityProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("Rejected unauthenticated request to {}: {}",
                request.getRequestURI(), authException.getMessage());

        // Advertises the scheme without revealing anything about why this attempt failed.
        response.setHeader("WWW-Authenticate", "Bearer");
        writer.write(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "A valid access token is required to call this endpoint.",
                request.getRequestURI());
    }
}
