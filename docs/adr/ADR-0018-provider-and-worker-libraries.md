# ADR-0018: Provider and worker libraries

| Field | Value |
|---|---|
| Status | Accepted (Phase 3) |
| Related | ADR-0003 (provider SPI), ADR-0010 (worker pools), PHASE-3-DESIGN §3–§4 |

## Context

Phase 3 implements the first providers and the worker runtime. The SPI (`provider-spi`) stays framework-free. Each provider is a plain Java library that depends only on the SPI and on its protocol library. The worker is a Spring Boot application that loads providers via `ProviderFactory` (ServiceLoader).

## Decision

| Concern | Library | Reason |
|---|---|---|
| SSH (linux-ssh) | Apache MINA SSHD (`sshd-core`) | Pure Java and actively maintained. Supports public-key auth, host-key verification, and exec channels with stdin. Apache-2.0. |
| LDAP/LDAPS (active-directory) | UnboundID LDAP SDK | Pure Java, no JNDI quirks, paged results, `unicodePwd` modify over LDAPS, and a strict TLS trust manager. GPL/LGPL/UnboundID Free Use; the LGPL/Free Use license is used. |
| WinRM (windows-winrm) | `java.net.http` + WS-Management SOAP built in the provider, NTLM via `jcifs-ng` | Avoids heavy CXF-based stacks. The provider sends PowerShell as `-EncodedCommand` with JSON-bound parameters. |
| SCIM / REST (generic-rest) | `java.net.http` + shared-kernel `Json` | No extra dependency. |
| Resilience (worker) | Resilience4j (`circuitbreaker`, `bulkhead`, `timelimiter`) | Per-instance registries and metrics via Micrometer. |
| Tests | Testcontainers (openssh-server, Samba AD DC, Toxiproxy), WireMock | Real protocol behaviour in CI. |

Versions live in `gradle/libs.versions.toml` and are updated with the other dependencies.

## Consequences

- The providers carry no Spring dependency. The ArchUnit rule "providers depend only on provider-spi" is extended to `providers/*`.
- A licence review of UnboundID (LGPL option) and jcifs-ng (LGPL) is recorded here. Both are used unmodified as libraries.
