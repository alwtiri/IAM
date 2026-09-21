# Contracts

Versioned, machine-checked contracts shared by the Core, workers, gateways, and clients.

| Contract | File | Consumers | Checked by |
|---|---|---|---|
| Core public REST API v1 | `openapi/iam-core-v1.yaml` | Web UI, automation clients | Redocly lint in CI; enum sync with `ErrorCode` / `Capability` |
| Operation command v1 | `messaging/operation-command.schema.json` | Outbox relay → worker pools | JSON Schema validation tests (Phase 2/3) |
| Operation result v1 | `messaging/operation-result.schema.json` | Worker pools → Core | JSON Schema validation tests (Phase 2/3) |
| Provider SPI v1 | `../provider-spi/` (Java) | Provider plugins, worker runtime | `provider-spi-testkit` contract checks |

Mutating-operation `SUCCEEDED` results carry a verification (enforced in the SPI type; the Core rejects a result message claiming success for a mutating operation without `verification`).
