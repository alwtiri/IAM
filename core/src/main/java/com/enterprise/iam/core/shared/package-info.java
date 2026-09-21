/**
 * Cross-cutting application concerns: security context, correlation, error mapping (spec §73).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Shared", allowedDependencies = {})
package com.enterprise.iam.core.shared;

import org.springframework.modulith.ApplicationModule;
