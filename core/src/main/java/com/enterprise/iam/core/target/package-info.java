/**
 * Targets, assets, dependencies, license/contract metadata (spec §10, §41–§43, §55).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Target", allowedDependencies = {"shared::*", "audit::api", "organization::api"})
package com.enterprise.iam.core.target;

import org.springframework.modulith.ApplicationModule;
