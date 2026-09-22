/**
 * Operation model, transactional outbox, idempotency (spec §46–§48, ADR-0006).
 *
 * <p>Allowed dependencies are enforced by Spring Modulith verification (ModularityTest) and ArchUnit.
 */
@ApplicationModule(displayName = "Operation", allowedDependencies = {"shared::*", "audit::api"})
package com.enterprise.iam.core.operation;

import org.springframework.modulith.ApplicationModule;
