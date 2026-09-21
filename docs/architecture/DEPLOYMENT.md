# Deployment Architecture — Docker-First

| Field | Value |
|---|---|
| Phase | 1 |
| Decision | ADR-0009 (Docker-first, Compose initial standard) |
| Implemented in this phase | `deploy/compose/` development stack (infrastructure + `iam-core`, `iam-web` skeletons) |

## 1. Images

| Image | Base | Runs as | Writable paths | Introduced |
|---|---|---|---|---|
| `iam-core` | Eclipse Temurin 21 JRE (distroless/ubi-minimal alternative evaluated Phase 9) | UID 10001 | `/tmp` (tmpfs) | 1 |
| `iam-web` | nginx-unprivileged (static files) | UID 101 | `/tmp`, `/var/cache/nginx` (tmpfs) | 1 |
| `iam-scheduler` | Temurin 21 JRE | UID 10001 | `/tmp` (tmpfs) | 2 |
| `iam-worker` (one image, started per group via `IAM_WORKER_POOLS`) | Temurin 21 JRE | UID 10001 | `/tmp` (tmpfs) | 3 |
| `pam-gateway-{ssh,rdp,web,db,net,api}` | per gateway | non-root | `/tmp`; recordings streamed to object store, local spool on dedicated volume if needed | 6 |
| `integration-*` | Temurin 21 JRE | UID 10001 | `/tmp` | 8 |

Build: multi-stage Dockerfiles; the build stage is never shipped. Images carry OCI labels (version, git SHA, build date), an SBOM (CycloneDX) and are scanned by Trivy in CI. No secrets in images or build args.

## 2. Compose stacks

| File | Purpose |
|---|---|
| `deploy/compose/compose.yaml` | Development/test stack: infrastructure + Core + web |
| `deploy/compose/compose.workers.yaml` | Worker groups (added in Phase 3) |
| `deploy/compose/compose.gateways.yaml` | Gateways (added in Phase 6) |
| `deploy/compose/compose.prod.yaml` | Production overrides (Phase 10): external secrets, resource limits, TLS certificates, log drivers |

Later-phase services are **not** declared before their phase (G10) — no placeholder services referencing images that do not exist.

## 3. Networks (trust zones)

| Network | Members | Notes |
|---|---|---|
| `edge` | iam-proxy, iam-web, iam-core, (gateways' browser endpoints) | Only network with published ports (proxy) |
| `core` | iam-core, iam-scheduler, postgres, keycloak, keycloak-db, vault, rabbitmq, cache | `internal: true` in production |
| `workers` | worker groups, rabbitmq, iam-core (credential API) | |
| `gw-<channel>` | one gateway + iam-core session API | One network per gateway |
| `targets` | worker groups, gateways | Egress to managed estate; Core is **not** attached |

## 4. Persistent state and backup

| Service | Volume | Backup method | Restore test |
|---|---|---|---|
| postgres (platform) | `pg-data` | `pg_basebackup` + WAL archiving (PITR); nightly logical dump for portability | Monthly automated restore into scratch container + integrity checks (row counts, audit hash-chain verification) |
| keycloak-db | `kc-db-data` | `pg_dump` nightly + realm export (without secrets) | Quarterly |
| vault | `vault-data` (integrated Raft storage) | `vault operator raft snapshot save` (encrypted) | Quarterly restore to isolated Vault + unseal drill |
| rabbitmq | `mq-data` | Definitions export; messages are reproducible from outbox | Recreate from definitions |
| cache | none (ephemeral) | Not backed up | n/a |
| object-store (Phase 6) | `obj-data` or external S3 | Replication / storage-native | Per Phase 10 |

Procedures are written in `docs/runbooks/` in Phase 10; the Phase 1 foundation fixes volume layout so that nothing persistent lives in container layers.

## 5. Container hardening baseline

Applied through the `x-hardening` Compose anchor to every application service: `read_only: true`, `tmpfs: [/tmp]`, `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]`, `user: "10001:10001"`, `pids_limit`, memory/CPU limits, healthcheck, `restart: unless-stopped`. Infrastructure images follow the same rules where the image supports it (exceptions are documented inline in the compose file with the reason). Forbidden without ADR + security review: `privileged`, Docker socket mounts, `network_mode: host`, `cap_add` beyond `NET_BIND_SERVICE`.

## 6. Configuration and secrets

- Configuration via environment variables with documented defaults (`.env.example`).
- Development secrets are generated locally by `deploy/compose/scripts/generate-dev-secrets.sh` into `deploy/compose/secrets/` (git-ignored) and mounted as Compose secrets (files under `/run/secrets`).
- Production: secrets from Vault (application AppRole bootstrap via Compose secret) or an external secret manager; never in `.env` committed files.

## 7. Kubernetes readiness mapping (future)

| Compose concept | Kubernetes equivalent |
|---|---|
| service (stateless) | Deployment + Service + HPA |
| service (stateful infra) | StatefulSet or external managed service |
| named volume | PersistentVolumeClaim |
| Compose secret | Secret / external-secrets / Vault Agent injector |
| network per zone | NetworkPolicy per namespace/label |
| healthcheck | liveness/readiness probes (`/actuator/health/liveness`, `/readiness`) |
| `x-hardening` | Pod Security Standard "restricted" + securityContext |

## 8. Environments (§70)

Development → Testing → Staging → Production. Each environment has its own Keycloak realm, Vault mount/namespace, PostgreSQL database, and RabbitMQ vhost. Production secrets never leave production; test data is synthetic.
