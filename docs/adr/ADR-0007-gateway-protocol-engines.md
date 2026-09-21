# ADR-0007: Gateway protocol engines: Apache MINA SSHD and Apache Guacamole guacd

- **Status:** Accepted (Phase 0 gate, 2026-09-21)
- **Date:** 2026-09-21
- **Spec references:** see Context

## Context

Phase 6 requires browser and native SSH/RDP access with credential injection, recording, and policy (clipboard, drive mapping, file transfer). Implementing RDP from scratch is impractical and risky.

## Decision

- **SSH gateway**: Java service using Apache MINA SSHD as both SSH server (for native clients) and SSH client (to targets), plus a websocket terminal for the browser (xterm.js). Recording in asciicast v2; command capture from the PTY stream (best-effort, documented limits) and, where an agent exists, from the host.
- **RDP gateway**: session broker service + Apache Guacamole `guacd` sidecar as protocol engine only. Guacamole's own web app, auth, and database are **not** used; the broker authorizes via Core grants and passes credentials to guacd per connection. Recording via guacd session recording, converted/stored per ADR-0008.
- Web, DB, and network gateways are designed in Phase 6 with their own ADRs.

## Consequences

+ Proven protocol implementations; small in-house surface.
- guacd is a C daemon: must be pinned, scanned (Trivy), and run in an isolated container with no access to Core secrets.
- Command auditing over SSH without an agent is inherently best-effort (shell aliases, scripts); this is stated in the capability model rather than hidden.

## Review

To be accepted at the Phase 0 gate. Superseding requires a new ADR (spec §60: reason, benefits, risks, migration implications, operational impact).
