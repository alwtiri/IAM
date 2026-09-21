package com.enterprise.iam.core.shared.api.context;

/** Correlation and client information of the current request (or job). */
public record RequestContext(String correlationId, String sourceIp, String userAgent) {

    public static RequestContext system(String correlationId) {
        return new RequestContext(correlationId, null, "system");
    }
}
