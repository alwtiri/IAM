/**
 * RBAC/scope-respecting enterprise search (spec §52). Read-only.
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Search", allowedDependencies = {"shared::api", "audit::api", "organization::api", "identity::api", "authorization::api", "account::api", "target::api", "provider::api", "request::api", "approval::api", "session::api", "operation::api"})
package com.enterprise.iam.core.search;

import org.springframework.modulith.ApplicationModule;
