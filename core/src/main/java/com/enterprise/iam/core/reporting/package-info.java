/**
 * Scope-filtered reports and official documents (spec §53–§54). Read-only.
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Reporting", allowedDependencies = {"shared::*", "audit::api", "organization::api", "identity::api", "authorization::api", "account::api", "target::api", "provider::api", "request::api", "approval::api", "session::api", "operation::api"})
package com.enterprise.iam.core.reporting;

import org.springframework.modulith.ApplicationModule;
