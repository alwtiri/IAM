/**
 * Access request engine and reviews (spec §20).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Request", allowedDependencies = {"shared::api", "audit::api", "identity::api", "account::api", "target::api", "policy::api", "risk::api", "sod::api", "authorization::api", "operation::api"})
package com.enterprise.iam.core.request;

import org.springframework.modulith.ApplicationModule;
