# ADR-0002: Modular-monolith Core with process-isolated extensions

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Spec §2–§3 and §83 require that extensions (gateways, agents, providers, integrations) never take down the IAM Core, and §48 requires deterministic synchronous authorization. Core concepts (identity, RBAC, requests, approvals, SoD, policy, risk, audit) need strong transactional consistency.

## Decision

`iam-core` is a single Spring Boot deployable organised into modules with enforced boundaries (Spring Modulith verification + ArchUnit in CI). All extensions run as separate processes/containers: `iam-worker` (hosting provider plugins), `pam-gateway-{ssh,rdp,web,db,net}`, `iam-agent`, `integration-*`.

Rules:
1. Core modules must not import extension code (build fails otherwise).
2. Core → extension communication is asynchronous via the transactional outbox (ADR-0006), or via narrow APIs protected by timeouts and circuit breakers.
3. Extension → Core communication uses versioned, mTLS-authenticated APIs.
4. Policy, SoD, and risk evaluation run in-process in the Core (no network hop on the decision path).

## Consequences

+ Security decisions are local, transactional, deterministic.
+ Extension crashes are contained by process isolation.
+ Simpler operations than a microservice mesh for the Core.
- Core scales as one unit (acceptable: stateless, horizontally scalable).
- If a Core module later needs independent scaling it can be extracted along its existing module boundary.
Alternatives rejected: full microservices (distributed transactions in the authorization path); single monolith including gateways (gateway failure/compromise would share the Core process).

## Amendment — Phase 0 gate (2026-09-21)

The single `iam-worker` is replaced by per-provider-group worker pools (ADR-0010). A separate `iam-scheduler` container is added (ADR-0009). `pam-gateway-api` is added as an independent gateway (gate clarification G7). Every deployable is its own container image (ADR-0009).

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
