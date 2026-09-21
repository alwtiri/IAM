/**
 * Provider registry, capability snapshots, provider/agent/gateway health (spec §11, §17–§18).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Provider", allowedDependencies = {"shared::api", "audit::api", "target::api", "secrets::api"})
package com.enterprise.iam.core.provider;

import org.springframework.modulith.ApplicationModule;
