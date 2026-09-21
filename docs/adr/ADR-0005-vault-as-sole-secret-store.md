# ADR-0005: Vault as the sole secret store; fail closed

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Spec §26–§29: no plaintext privileged credentials in ordinary DB fields; versioning, rotation, reveal history, dual control. §3/§4: if Vault is unavailable, secret-dependent operations fail safely without exposing credentials.

## Decision

All secret values (passwords, SSH keys, API keys, tokens, private keys, DB credentials) live in HashiCorp Vault (KV v2 for static secrets, Database engine for dynamic credentials where supported, PKI for internal mTLS, Transit for signing and field encryption). PostgreSQL stores only metadata and Vault paths/versions.

- No secret caching in plaintext outside Vault beyond the lifetime of a single operation.
- Rotation is two-phase (stage PENDING version → change target → verify → promote).
- Reveal and checkout require policy evaluation, may require approval and dual control, and are always audited.
- Vault unavailable → `SECRETS_UNAVAILABLE`; non-secret Core functionality continues.

## Consequences

+ Single, auditable secret boundary.
- Vault becomes an HA-critical component (Phase 10: integrated storage HA, auto-unseal).
- Break-glass recovery of Vault itself requires an offline, dual-controlled procedure (unseal/recovery keys) documented in the DR guide.

## Amendment — Phase 0 gate (2026-09-21)

Normative (gate clarification G3): Vault failure must not disable identity, organization, RBAC, policy management, request creation, approval workflows, audit, reporting, target metadata, non-secret provider metadata, or health. Only secret-requiring operations fail, with `SECRETS_UNAVAILABLE`. Operations whose fulfilment needs a secret are queued and wait (with timeout) rather than failing silently. No plaintext secret cache as an availability workaround.

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
