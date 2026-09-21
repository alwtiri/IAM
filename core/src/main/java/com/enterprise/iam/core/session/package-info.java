/**
 * Access grants, JIT, session registry, recording metadata (spec §37–§40).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Session", allowedDependencies = {"shared::api", "audit::api", "request::api", "account::api", "secrets::api", "policy::api", "target::api"})
package com.enterprise.iam.core.session;

import org.springframework.modulith.ApplicationModule;
