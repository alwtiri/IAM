package com.enterprise.iam.core.shared.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the permission an API handler requires (SEC1). The web layer denies the request before the handler runs
 * unless the actor holds the permission in at least one scope; the application service then performs the
 * object-level check. {@code EndpointSecurityCoverageTest} fails the build for handlers without this annotation
 * or {@link PublicEndpoint}/{@link AuthenticatedEndpoint}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {
    String value();
}
