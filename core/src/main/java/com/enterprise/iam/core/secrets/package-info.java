/**
 * Credential metadata and the only Vault client; credential handles (spec §26–§29, ADR-0005).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Secrets", allowedDependencies = {"shared::*", "audit::api", "operation::api"})
package com.enterprise.iam.core.secrets;

import org.springframework.modulith.ApplicationModule;
