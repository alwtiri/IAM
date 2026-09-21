/**
 * Account Management — Core governance of all account classes (spec §8, gate clarification G8).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Account", allowedDependencies = {"shared::api", "audit::api", "identity::api", "target::api", "provider::api", "operation::api", "secrets::api"})
package com.enterprise.iam.core.account;

import org.springframework.modulith.ApplicationModule;
