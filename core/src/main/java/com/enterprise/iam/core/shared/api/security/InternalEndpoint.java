package com.enterprise.iam.core.shared.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Handler of the internal API ({@code /internal/v1}) for worker-plane components. Reachable only on the internal mTLS
 * listener with a client certificate of a registered worker ({@link WorkerPrincipal}); never through the proxy or the
 * public listener (SECURITY-ARCHITECTURE §2, PHASE-3-DESIGN §6).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InternalEndpoint {
}
