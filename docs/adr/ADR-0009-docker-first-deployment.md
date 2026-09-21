# ADR-0009: Docker-first deployment, Docker Compose as initial standard

- **Status:** Accepted (gate clarification G1)
- **Date:** 2026-09-21

## Context

Gate clarification G1 makes Docker-first delivery mandatory, with Compose as the initial deployment standard and future Kubernetes suitability without domain redesign.

## Decision

1. **One image per deployable:** `iam-web`, `iam-core`, `iam-scheduler`, `iam-worker-<group>` (one per provider group), `pam-gateway-{ssh,rdp,web,db,net,api}`, `integration-<name>`, plus infrastructure images (PostgreSQL, Keycloak, Vault, RabbitMQ, Redis-compatible cache, reverse proxy, object store when introduced).
2. **Statelessness:** application containers hold no persistent state in their writable layer. Persistent state lives only in PostgreSQL, Vault storage, RabbitMQ, and (later) object storage — each on a **named volume** with a documented backup and restore procedure.
3. **Hardening defaults** (Compose `x-hardening` anchor, applied to every application service): non-root user (UID ≥ 10001), `read_only: true`, `tmpfs` for `/tmp`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, resource limits, healthchecks. No `privileged: true`, no Docker socket mounts, no host networking. Any exception needs a new ADR plus security review (e.g. `guacd` is expected to run without privileges; verified in Phase 6).
4. **Configuration:** 12-factor environment variables; secrets via Docker secrets / files mounted read-only (never baked into images, never committed). `.env.example` is committed, `.env` is git-ignored.
5. **Networks:** separate Compose networks per trust zone — `edge` (reverse proxy ↔ web/core), `core` (core ↔ PostgreSQL, Vault, RabbitMQ, cache, Keycloak), `workers` (workers ↔ RabbitMQ/core API), `gateways` (one network per gateway ↔ core session API), `targets` (egress to managed infrastructure; only workers and gateways attach).
6. **Kubernetes readiness:** each service maps 1:1 to a Deployment/StatefulSet; health endpoints split into liveness/readiness; no reliance on Compose-only features (links, container names) inside application code — only DNS service names from configuration.
7. **External managed infrastructure** (AD, servers, VMware, storage, network, databases) stays outside Docker and is reached through secure protocols, APIs, or optional agents.

## Consequences

+ Uniform packaging, isolation, and upgrade per component (§83 Q7–Q9).
+ Clean path to Kubernetes/Helm later.
- More images to build and scan; mitigated by one CI matrix and shared base images.
- Compose is single-host: HA for Compose deployments relies on external PostgreSQL/Vault HA until Kubernetes or multi-host is adopted (Phase 10).
