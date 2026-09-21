# ADR-0010: Worker pools, per-provider queues and bulkheads

- **Status:** Accepted (gate clarifications G4, G5)
- **Date:** 2026-09-21

## Context

Gate clarification G4: worker execution must not be a global bottleneck; a slow or unavailable provider must not exhaust capacity needed by other providers (VMware outage ≠ Linux blocked; DB outage ≠ AD blocked; storage outage ≠ Windows blocked).

## Decision

- **Queue topology:** exchange `iam.ops` (topic). One durable quorum queue per provider type: `ops.linux`, `ops.windows-local`, `ops.ad`, `ops.application`, `ops.vmware`, `ops.ovm`, `ops.hpe-3par`, `ops.hpe-storeonce`, `ops.hpe-oneview`, `ops.network`, `ops.database`, `ops.cloud`, plus `ops.discovery.<type>` for long-running discovery so it cannot starve lifecycle operations. Large estates may shard per provider *instance* (`ops.<type>.<instanceId>`). Each queue has a dead-letter queue `<queue>.dlq` and a retry queue with TTL-based back-off.
- **Pools:** a worker container runs one or more *pools*; each pool consumes exactly one queue with its own prefetch and concurrency limit, its own thread pool (bulkhead), per-provider-instance circuit breaker, connection/operation timeouts, and retry policy. Default deployment groups: `iam-worker-os` (linux, windows-local), `iam-worker-directory` (ad, application), `iam-worker-virt` (vmware, ovm), `iam-worker-storage` (hpe-*), `iam-worker-network`, `iam-worker-database`, `iam-worker-cloud`. Groups can be split further without code change.
- **Circuit open behaviour:** messages for an open provider instance are not consumed in a hot loop; the pool pauses that instance's consumption (or re-queues to the delay queue) and the Core marks capabilities `UNAVAILABLE`.
- **Back-pressure:** the outbox relay tracks per-queue depth; beyond a threshold it stops relaying for that queue only and the Core reports the operation as `QUEUED` with a back-pressure reason. Other queues continue.
- **Idempotency:** every command has `operationId` + `idempotencyKey`; workers record processed keys and providers implement idempotent semantics (create existing → no-op with verification).
- **Result path:** workers publish results to `iam.ops.results`; the Core consumer updates the Operation (idempotently) and reconciles account state. Workers never write the platform database directly.
- **Dead letters:** poison messages after max retries go to DLQ, raise an alert, and the Operation becomes `FAILED` or `UNKNOWN` (never `SUCCESS`).

## Consequences

+ Provider outages are contained to their own queue, pool, and container.
+ Capacity can be scaled per provider group.
- More queues and pools to monitor; mitigated by per-queue metrics and System Health.
- Ordering is only guaranteed per queue; operations on the same account are serialized by the Core (per-account operation lock) rather than by the broker.
