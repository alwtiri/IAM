# Requirements Traceability Matrix

`rtm.csv` maps every section of the authoritative specification (§1–§84) to its target phase, ADRs, architecture,
implementation, API, UI, database, tests, documentation, and acceptance criteria (spec §80).

Status values: `PLANNED` → `DESIGNED` → `PARTIAL` (part of the requirement delivered; remainder named with its phase) →
`IMPLEMENTED` → `VERIFIED` (runtime evidence in CI/UAT) → `ACCEPTED`. A requirement is `VERIFIED` only when a test
reference exists **and** the tests ran green in CI.
