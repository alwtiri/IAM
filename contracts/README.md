# Contracts

Versioned, machine-checked contracts shared by the Core, workers, gateways, and clients.

| Contract | File | Consumers | Checked by |
|---|---|---|---|
| Core public REST API v1 | `openapi/iam-core-v1.yaml` | Web UI, automation clients | Redocly lint in CI; enum sync with `ErrorCode` / `Capability` |
| Operation command v1 | `messaging/operation-command.schema.json` | Outbox relay → worker pools | JSON Schema validation tests (Phase 2/3) |
| Operation result v1 | `messaging/operation-result.schema.json` | Worker pools → Core | JSON Schema validation tests (Phase 2/3) |
| Provider SPI v1 | `../provider-spi/` (Java) | Provider plugins, worker runtime | `provider-spi-testkit` contract checks |

Mutating-operation `SUCCEEDED` results carry a verification (enforced in the SPI type; the Core rejects a result message claiming success for a mutating operation without `verification`).

## Worker plane messages (Phase 3)

| Aspect | Rule |
|---|---|
| Queues | Exchange `iam.ops` (topic), one durable queue per provider type `ops.<type>` with routing key `ops.<type>`, dead-letter exchange `iam.ops.dlx` → `ops.dlq`, `x-max-length` 100 000 with `reject-publish`. Core and worker declare them with identical arguments. |
| Results | Exchange `iam.ops.results` → Core queue `iam.core.results` (routing key `#`). The Core de-duplicates on `messageId` and ignores results of an older `attempt`. |
| Credentials | `credentialHandles` holds purpose → single-use handle (`ch_…`). The worker redeems a handle with `POST /internal/v1/credential-handles:redeem {"handle": …}` over mTLS (client certificate CN registered in `iam.internal-api.workers`). The response is `{"value": …}` with `Cache-Control: no-store`. |
| Account payloads | Serialized SPI `AccountState`: `{"account":{"nativeId","name"},"status","privileged","groups":[{"nativeId","name"}],"lastLogin","passwordLastSet","passwordExpires","attributes":{…}}`. `attributes.displayName` and `attributes.privilegeReason` are lifted out by the Core; secret-like attribute keys are dropped. |
| Discovery | `DISCOVER_ACCOUNTS` sends one `PROGRESS` message per page with `resultPayload.accounts` (and optionally `groups`) plus `sequence`, then a final `SUCCEEDED`/`FAILED` message; the final message may carry the last page. Only a `SUCCEEDED` run marks unreported accounts `ABSENT`. |
| Lifecycle | `ENABLE_ACCOUNT`, `DISABLE_ACCOUNT`, `UNLOCK_ACCOUNT` and `GET_ACCOUNT_STATE` return the observed state as `resultPayload.state`. Mutating operations also return a `verification`; otherwise the Core records `UNKNOWN`. |
