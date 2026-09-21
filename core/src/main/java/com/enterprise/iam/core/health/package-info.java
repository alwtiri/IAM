/**
 * System health aggregation and affected-functionality mapping (spec §62).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Health", allowedDependencies = {"shared::api"})
package com.enterprise.iam.core.health;

import org.springframework.modulith.ApplicationModule;
