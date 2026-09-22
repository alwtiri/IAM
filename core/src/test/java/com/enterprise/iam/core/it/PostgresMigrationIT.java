package com.enterprise.iam.core.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.application.AuditService;
import com.enterprise.iam.core.audit.domain.ChainVerifier;
import com.enterprise.iam.core.audit.infrastructure.persistence.JdbcAuditStore;
import com.enterprise.iam.core.operation.api.OutboxMessage;
import com.enterprise.iam.core.operation.infrastructure.persistence.JdbcOutboxStore;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.testsupport.TestSupport;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL (Testcontainers): all migrations apply as the owner role; audit rows are immutable even for the
 * owner; the hash chain written by the JDBC adapter verifies; outbox claims are exclusive (SKIP LOCKED).
 */
@Testcontainers
class PostgresMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.4-alpine");

    static JdbcClient jdbc;
    static TransactionRunner tx;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("platform").createSchemas(true).locations("classpath:db/migration").load().migrate();
        // Spring's DriverManagerDataSource keeps the PostgreSQL driver runtime-only (no compile dependency on org.postgresql).
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(ds);
        TransactionTemplate tt = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx = new TransactionRunner() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> work) {
                return tt.execute(s -> work.get());
            }

            @Override
            public <T> T readOnly(java.util.function.Supplier<T> work) {
                return tt.execute(s -> work.get());
            }
        };
    }

    @Test
    void seedDataIsPresent() {
        // The seeded catalog must equal the code catalog (grows with each phase; never a hard-coded count).
        assertEquals(java.util.Set.copyOf(Permissions.ALL),
                java.util.Set.copyOf(jdbc.sql("SELECT code FROM \"authorization\".permission").query(String.class).list()));
        assertEquals(11L, jdbc.sql("SELECT count(*) FROM \"authorization\".role WHERE built_in").query(Long.class).single());
        assertEquals("ACTIVE", jdbc.sql("SELECT state FROM identity.identity WHERE username = 'system'").query(String.class).single());
    }

    @Test
    void auditChainIsWrittenVerifiedAndImmutable() {
        JdbcAuditStore store = new JdbcAuditStore(jdbc);
        AuditService audit = new AuditService(store, TestSupport.CONTEXT, TestSupport.guard(true), tx, Clock.systemUTC());
        for (int i = 0; i < 5; i++) {
            int n = i;
            tx.run(() -> audit.record(TestSupport.actor(UUID.randomUUID()),
                    AuditEntry.success("identity.updated", "identity", "i" + n, Map.of("n", String.valueOf(n)))));
        }
        ChainVerifier v = new ChainVerifier();
        store.streamPartition("global", v::accept);
        v.acceptHead(store.readHead("global"));
        assertTrue(v.valid(), v.problem());

        assertThrows(Exception.class, () -> jdbc.sql("UPDATE audit.audit_event SET result = 'FAILURE'").update());
        assertThrows(Exception.class, () -> jdbc.sql("DELETE FROM audit.audit_event").update());
        assertThrows(Exception.class, () -> jdbc.sql("TRUNCATE audit.audit_event").update());
    }

    @Test
    void mutatingOperationCannotBeSuccessWithoutVerification() {
        assertThrows(Exception.class, () -> jdbc.sql("""
                INSERT INTO operation.operation (id, type, mutating, status, created_at, idempotency_key)
                VALUES (gen_random_uuid(), 'ACCOUNT_DISABLE', true, 'SUCCESS', now(), 'k-1')""").update());
    }

    @Test
    void outboxClaimsAreExclusive() {
        JdbcOutboxStore outbox = new JdbcOutboxStore(jdbc);
        Instant now = Instant.now();
        tx.run(() -> {
            for (int i = 0; i < 4; i++) {
                outbox.insert(new OutboxMessage(UUID.randomUUID(), "smtp", "n", "x", Map.of("i", i), Map.of(), 0, now), now);
            }
        });
        List<OutboxMessage> first = tx.inTransaction(() -> outbox.claimDue(now.plusSeconds(1), now.plusSeconds(60), 10));
        List<OutboxMessage> second = tx.inTransaction(() -> outbox.claimDue(now.plusSeconds(1), now.plusSeconds(60), 10));
        assertTrue(first.size() >= 4);
        assertTrue(second.isEmpty(), "leased messages are not claimed twice");
    }
}
