package com.enterprise.iam.core.shared.infrastructure.web;

import com.enterprise.iam.kernel.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts a safe {@code X-Correlation-Id} or generates one, exposes it to logs (MDC), audit, and the response
 * (spec §67). Runs before Spring Security so even rejected requests carry a correlation id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId id = CorrelationId.fromUntrusted(request.getHeader(CorrelationId.HEADER));
        request.setAttribute(ATTRIBUTE, id.value());
        response.setHeader(CorrelationId.HEADER, id.value());
        MDC.put(MDC_KEY, id.value());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
