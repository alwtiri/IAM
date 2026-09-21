# ADR-0015: Persistence with Spring JdbcClient and explicit SQL

- **Status:** Accepted (Phase 2)
- **Date:** 2026-09-21

## Context

Spec §60 lists Spring Data JPA / Hibernate as the preferred persistence stack and requires an ADR for major alternatives. Phase 2 persistence is dominated by append-only audit with hash chaining, the transactional outbox with `FOR UPDATE SKIP LOCKED`, keyset pagination, and scope filters that must be applied in SQL (§52, SEC2). These need exact control over locking, ordering, and generated SQL; ORM features (lazy loading, dirty checking, cascades) add risk of N+1 queries (§63) and accidental updates of immutable rows.

## Decision

- Core modules use **Spring `JdbcClient`** (Spring Framework 6.1+) with explicit, reviewed SQL in module-owned repository adapters (`infrastructure.persistence`). Domain objects stay persistence-agnostic.
- Optimistic locking is explicit (`version` column in `WHERE` clause; zero rows updated → `CONCURRENT_MODIFICATION`).
- Spring Data JPA/Hibernate remains permitted for a future module whose model benefits from it, via a module-level ADR; mixing within one module is not allowed.
- Flyway remains the only schema authority (`spring.jpa.hibernate.ddl-auto` is never used).

## Consequences

+ Predictable SQL, locking, and performance; no N+1 by construction; append-only tables cannot be updated through an ORM flush.
+ Repositories are simple to test with Testcontainers.
- More mapping code (row mappers) than JPA; mitigated by small, focused repositories.
- Migration cost back to JPA is low: domain objects are independent of the persistence technology.
