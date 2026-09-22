/**
 * Approval workflows and immutable decisions (spec §21).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Approval", allowedDependencies = {"shared::*", "audit::api", "request::api", "sod::api", "identity::api"})
package com.enterprise.iam.core.approval;

import org.springframework.modulith.ApplicationModule;
