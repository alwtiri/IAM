# ADR-0006: Transactional outbox for messaging; Redis as cache only

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Spec §3: if RabbitMQ is unavailable, synchronous critical operations must not silently disappear; if Redis is unavailable, the system degrades gracefully.

## Decision

Any business change that needs asynchronous follow-up (provisioning, notification, session termination command, SIEM export) writes an outbox row in the same PostgreSQL transaction. A relay publishes outbox rows to RabbitMQ (quorum queues, publisher confirms) and marks them sent; consumers are idempotent (idempotency key per operation). If RabbitMQ is down, rows accumulate and are published on recovery; outbox backlog is a health metric.

Redis is used only for caching (authorization lookups, revocation list cache, UI read models) and rate-limit counters. It is never the source of truth. On Redis failure: cache bypass to PostgreSQL; rate limiting falls back to per-instance in-memory limits; health shows DEGRADED.

## Consequences

+ No lost work; exactly-once effect through at-least-once delivery + idempotent handlers.
+ Redis outage has no functional impact.
- Outbox table needs housekeeping (archival of sent rows).

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
