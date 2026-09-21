/**
 * Organization, business units, departments, teams, positions, locations (spec §5).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Organization", allowedDependencies = {"shared::api", "audit::api"})
package com.enterprise.iam.core.organization;

import org.springframework.modulith.ApplicationModule;
