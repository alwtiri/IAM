/**
 * Explainable risk assessment (spec §24).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Risk", allowedDependencies = {"shared::*"})
package com.enterprise.iam.core.risk;

import org.springframework.modulith.ApplicationModule;
