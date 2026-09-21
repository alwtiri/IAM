/**
 * Person, Identity, Platform User, joiner/mover/leaver (spec §6–§7).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"shared::api", "audit::api", "organization::api"})
package com.enterprise.iam.core.identity;

import org.springframework.modulith.ApplicationModule;
