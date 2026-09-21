/**
 * Central policy model and deterministic evaluator (spec §23, ADR-0013).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Policy", allowedDependencies = {"shared::api"})
package com.enterprise.iam.core.policy;

import org.springframework.modulith.ApplicationModule;
