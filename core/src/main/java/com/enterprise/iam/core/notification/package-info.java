/**
 * Notification templates and outbox-driven delivery (spec §57).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Notification", allowedDependencies = {"shared::*", "operation::api", "audit::api", "identity::api", "authorization::api", "request::api"})
package com.enterprise.iam.core.notification;

import org.springframework.modulith.ApplicationModule;
