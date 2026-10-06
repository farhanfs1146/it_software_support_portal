package com.forward.it_software_support_portal.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes RFC 7807 problem documents from inside the security filter chain.
 *
 * <p>Security failures happen before Spring MVC's exception handling is reachable, so
 * {@code GlobalExceptionHandler} never sees them. Rather than let those responses fall back to a
 * different shape, this writes the same {@code type/title/status/detail/instance} document by hand, so
 * a client parses 401 and 403 exactly as it parses 404 and 409.
 *
 * <p>Built as a literal string rather than through an {@code ObjectMapper} on purpose: it keeps the
 * writer free of any dependency on the application's JSON configuration, which is the sort of coupling
 * that turns a misconfigured serializer into a broken error response.
 */
@Component
public class SecurityProblemWriter {

    public void write(HttpServletResponse response,
                      HttpStatus status,
                      String title,
                      String detail,
                      String instance) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","instance":"%s"}"""
                .formatted(escape(title), status.value(), escape(detail), escape(instance)));
    }

    /** Minimal JSON string escaping; the inputs here are application-authored, never user data. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
