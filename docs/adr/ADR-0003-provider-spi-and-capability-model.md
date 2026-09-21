# ADR-0003: Provider SPI and explicit capability model

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Spec §11–§12, §18, §64: providers must expose explicit capabilities, never fake unsupported ones, and be isolated with timeouts and circuit breakers. Future providers must be addable without redesigning the Core.

## Decision

Define a versioned `provider-spi` module containing:
- `Provider` interface with the §11 operations (validate_connection … reconcile).
- `Capability` enum (ACCOUNT_DISCOVERY, ACCOUNT_CREATE, ACCOUNT_DISABLE, PASSWORD_ROTATION, GROUP_MANAGEMENT, PRIVILEGE_MANAGEMENT, TARGET_DISCOVERY, SESSION_ACCESS, COMMAND_EXECUTION, FILE_TRANSFER, AUDIT, …) and a `CapabilityDescriptor` declared by each provider (optionally version-dependent, determined at `validate_connection`).
- A result type distinguishing SUCCESS (verified), FAILED, TIMEOUT, PARTIAL, UNKNOWN, and the error `UNSUPPORTED_CAPABILITY` with explanation.
- Mandatory `verify_operation` semantics: no SUCCESS without verification where technically possible.
- Idempotency key on every mutating call.
- A shared contract-test kit every provider must pass.

Providers run only inside `iam-worker`, each provider instance with its own Resilience4j bulkhead, timeouts, retry policy, and circuit breaker; health and failure reason are published to the Core health aggregator. Credentials are passed as short-lived Vault-sourced handles, never persisted by the provider.

## Consequences

+ New providers are plugins; Core unchanged.
+ Honest capability reporting drives UI states (supported / unsupported / unavailable / degraded / agent-required).
- Contract-test kit is an upfront investment in Phase 1/3.

## Amendment — Phase 0 gate (2026-09-21)

Normative (gate clarification G5): capability status values are `SUPPORTED`, `UNSUPPORTED`, `UNAVAILABLE`, `DEGRADED`, `AGENT_REQUIRED`. The SPI enforces that an operation result of `SUCCEEDED` carries a verification record. Providers execute target-side operations only; the Core `account` module owns lifecycle and governance (G8).

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
