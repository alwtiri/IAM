package com.enterprise.iam.core.shared.infrastructure.web;

import com.enterprise.iam.core.shared.api.context.RequestContext;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.kernel.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Request context from the current servlet request, or a generated system context for jobs. */
@Component
class ServletRequestContextProvider implements RequestContextProvider {

    @Override
    public RequestContext current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            Object id = req.getAttribute(CorrelationIdFilter.ATTRIBUTE);
            String ua = req.getHeader("User-Agent");
            return new RequestContext(id != null ? id.toString() : CorrelationId.generate().value(),
                    req.getRemoteAddr(), ua == null ? null : ua.substring(0, Math.min(ua.length(), 256)));
        }
        String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        return RequestContext.system(mdc != null ? mdc : CorrelationId.generate().value());
    }
}
