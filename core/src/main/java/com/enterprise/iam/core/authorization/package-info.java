/**
 * Roles, permissions, scopes, server-side RBAC decisions (spec §19).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Authorization", allowedDependencies = {"shared::api", "audit::api", "identity::api", "organization::api"})
package com.enterprise.iam.core.authorization;

import org.springframework.modulith.ApplicationModule;
