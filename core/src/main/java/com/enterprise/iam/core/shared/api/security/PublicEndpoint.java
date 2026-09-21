package com.enterprise.iam.core.shared.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Handler that is intentionally reachable without authentication. Each use needs a justification. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {
    /** Why this endpoint is public. */
    String reason();
}
