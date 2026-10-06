package com.forward.it_software_support_portal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Emits <strong>403 Forbidden</strong> as an RFC 7807 problem document when an authenticated caller
 * lacks the required permission.
 *
 * <p>The body names no permission, role or rule. Telling a caller exactly which authority they are
 * missing maps out the authorization model for them; the detail is logged instead.
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAccessDeniedHandler.class);

    private final SecurityProblemWriter writer;

    public ProblemDetailAccessDeniedHandler(SecurityProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.info("Denied request to {} {}: {}",
                request.getMethod(), request.getRequestURI(), accessDeniedException.getMessage());

        writer.write(response, HttpStatus.FORBIDDEN,
                "Access denied",
                "You do not have permission to perform this operation.",
                request.getRequestURI());
    }
}
