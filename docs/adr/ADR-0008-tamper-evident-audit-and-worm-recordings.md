# ADR-0008: Tamper-evident audit and WORM storage for recordings

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Spec §49–§51: audit is first-class, tamper-evident, deletion heavily restricted; §38: recordings protected from unauthorized modification.

## Decision

- Audit events are written to an append-only PostgreSQL table in the same transaction as the audited action. Triggers reject UPDATE and DELETE; the application DB role has INSERT/SELECT only.
- Each event carries `prev_hash` and `hash = SHA-256(prev_hash || canonical_json(event))` per chain partition; periodic checkpoints are signed with a Vault Transit key.
- Sealed audit segments and session recordings are stored in S3-compatible object storage with Object Lock (compliance mode) — e.g. MinIO on-prem — with per-object SHA-256 recorded in PostgreSQL.
- A verifier job and API validate chain and object integrity; failures raise security alerts.
- Retention deletion only via policy, under dual control, itself audited.
- Privileged/emergency operations are denied if the audit write fails (Phase 0 G-07).

## Consequences

+ Tampering is detectable; recordings immutable for the retention period.
- Object store is a new component to operate and back up.
- Hash chain requires serialised writes per partition; partition by day/tenant keeps throughput acceptable.

## Amendment — Phase 0 gate (2026-09-21)

Product choice for the S3-compatible Object-Lock store is deferred to Phase 6 (recordings) — the MinIO community distribution model changed in 2025, so MinIO, Ceph RGW, SeaweedFS, and existing enterprise S3 storage will be compared then. The Core audit hash chain and in-transaction writes do not depend on this choice.

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
