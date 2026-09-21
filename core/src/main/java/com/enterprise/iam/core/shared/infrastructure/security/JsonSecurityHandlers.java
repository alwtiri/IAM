package com.enterprise.iam.core.shared.infrastructure.security;

import com.enterprise.iam.core.shared.infrastructure.web.CorrelationIdFilter;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.Json;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Writes 401/403 for API requests in the structured error model instead of redirects or HTML (spec §73). */
final class JsonSecurityHandlers {

    private JsonSecurityHandlers() {
    }

    static AuthenticationEntryPoint unauthorized(Clock clock) {
        return (req, res, ex) -> write(req, res, ErrorCode.AUTHENTICATION_REQUIRED, "Authentication required", clock);
    }

    static AccessDeniedHandler forbidden(Clock clock) {
        return (req, res, ex) -> write(req, res, ErrorCode.ACCESS_DENIED, "Access denied", clock);
    }

    private static void write(HttpServletRequest req, HttpServletResponse res, ErrorCode code, String message, Clock clock)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message);
        body.put("retryable", false);
        body.put("timestamp", clock.instant().toString());
        Object corr = req.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        body.put("correlationId", corr == null ? null : corr.toString());
        body.put("details", List.of());
        res.setStatus(code.httpStatus());
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(Json.write(body));
    }
}
