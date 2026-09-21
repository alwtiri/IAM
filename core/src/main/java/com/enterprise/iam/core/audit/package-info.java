/**
 * Append-only, hash-chained audit and evidence links (spec §49–§51, ADR-0008).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = {"shared::api"})
package com.enterprise.iam.core.audit;

import org.springframework.modulith.ApplicationModule;
