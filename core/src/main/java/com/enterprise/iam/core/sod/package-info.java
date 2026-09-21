/**
 * Segregation of duties rules, conflict matrix, exceptions (spec §22).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Sod", allowedDependencies = {"shared::api", "audit::api", "authorization::api"})
package com.enterprise.iam.core.sod;

import org.springframework.modulith.ApplicationModule;
