# High availability and scale (Phase 10)

The Compose stack is a single-host installation for labs and small estates. Production HA uses the same images.

| Component | HA approach | Notes |
|---|---|---|
| iam-core | 2+ replicas behind the proxy/load balancer | stateless; sessions in the cache; scheduled jobs are idempotent (discovery skips recent runs, expiry uses row locks, rotation locks the credential row) |
| iam-worker | N replicas per provider type | one durable queue per type (ADR-0010); scale the busy types; handles are single-use so a crashed worker's operation ends UNKNOWN, never double-applied silently |
| PostgreSQL | primary + synchronous standby (Patroni or managed service) | point-in-time recovery; `backup.sh` is the logical backup on top |
| Vault | 3 or 5 node Raft cluster, auto-unseal (KMS/HSM) | platform fails closed when Vault is unavailable (G3) |
| RabbitMQ | 3-node quorum queues | outbox guarantees no lost commands during failover |
| Keycloak | 2+ nodes with its own HA database | login and step-up depend on it |
| Proxy | 2 nodes + VIP (keepalived) or cloud LB | stateless; keep rate-limit zones per node |

## Sizing guide
| Estate | core | workers | PostgreSQL |
|---|---|---|---|
| ≤ 500 servers, ≤ 20 000 accounts | 2 × 2 vCPU / 2 GB | 2 × 1 vCPU / 1 GB | 2 vCPU / 4 GB |
| ≤ 5 000 servers | 3 × 4 vCPU / 4 GB | 6 × 2 vCPU / 2 GB | 8 vCPU / 16 GB |

Discovery load is spread over the day (`iam.discovery.interval`, checked every 15 min); raise worker concurrency per
type in the worker configuration before adding replicas.
