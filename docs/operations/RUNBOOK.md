# Operations runbook (Phase 11)

All commands run on the platform host from `/opt/IAM-PAM-Platform/IAM/deploy/compose` unless stated otherwise.

## Daily
| Check | How | Expected |
|---|---|---|
| Health | Administration → System Health | all components HEALTHY (SMTP may be "not configured" in labs) |
| Operations needing attention | Dashboard KPI | 0, or each one explained (UNKNOWN means "verify on the target") |
| Emergency uses | Emergency Access → Reviews | none pending older than one working day |
| Vault | Privileged Access → Password Vault | every row VERIFIED; UNKNOWN/FAILED investigated (hover shows the reason) |

## Update to a new version
```bash
cd /opt/IAM-PAM-Platform/IAM
git fetch /root/<bundle> phase-3-core-providers:tmp-up && git merge --ff-only tmp-up && git branch -d tmp-up
./deploy/compose/scripts/update-stack.sh      # build + tests, secrets, Keycloak settings, rebuild, health checks
./deploy/compose/scripts/acceptance-check.sh  # edge and hygiene checks
git push
```
`update-stack.sh` refuses to run with uncommitted changes to tracked files: commit or stash them first.

## Backup and restore
```bash
./scripts/backup.sh /var/backups/iam          # IAM DB, Keycloak DB, Vault Raft snapshot, secrets; keeps 14
./scripts/restore.sh /var/backups/iam/<ts> --yes
```
Schedule the backup daily (cron: `30 2 * * * cd /opt/IAM-PAM-Platform/IAM/deploy/compose && ./scripts/backup.sh`),
copy the folder off the host encrypted (it contains the Vault snapshot and the secrets), and test a restore on a
spare host every quarter.

## Incidents
| Symptom | Likely cause | Action |
|---|---|---|
| Blank page after an update | browser cache / proxy kept old upstream | `Ctrl+F5`; `docker compose restart iam-proxy` |
| Every sensitive action asks for MFA again | step-up older than `iam.auth.step-up.max-age` (5 min) | expected; sign in with MFA again |
| Operations stay QUEUED | worker down or RabbitMQ unavailable | `docker compose ps iam-worker rabbitmq`; `docker compose logs --tail=100 iam-worker` |
| SECRETS_UNAVAILABLE | Vault sealed after a restart | `./scripts/vault-init-dev.sh` (dev: unseals with the stored key) |
| Connection test fails with host key error | the server's SSH host key changed | verify the new key with the server owner, then edit the connection's `hostKeyFingerprint` |
| Rotation UNKNOWN | change sent but not confirmed (timeout, pg_hba, lockout) | reveal offers both values; fix the cause and use "Rotate now" |
| 429 Too Many Requests | rate limit at the edge | wait a few seconds; raise limits in `proxy/default.conf` for large NATs |

## Emergency (break-glass)
1. Emergency Access → Break Glass → choose the account → describe the emergency.
2. My Checkouts → Show password (MFA). Security administrators receive an e-mail immediately.
3. Check in when done; the password is rotated automatically. A security administrator reviews the use.

## Contacts and escalation
Record the on-call rota, Vault key custodians and the SIEM owner here for your organisation.
