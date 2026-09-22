package com.enterprise.iam.core.operation.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.operation.api.ProviderCommand;
import com.enterprise.iam.core.operation.application.OperationStore;
import com.enterprise.iam.core.operation.domain.Operation;
import com.enterprise.iam.core.operation.domain.OperationStatus;
import com.enterprise.iam.kernel.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcOperationStore implements OperationStore {

    private final JdbcClient jdbc;

    public JdbcOperationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Operation op, ProviderCommand c, Instant createdAt, Instant deadline, String correlationId) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", op.id());
        p.put("type", op.type());
        p.put("mutating", op.mutating());
        p.put("ptype", c.providerType());
        p.put("pinst", c.providerInstanceId());
        p.put("target", c.targetId());
        p.put("account", c.accountId());
        p.put("requester", c.requesterId());
        p.put("org", c.scopeOrgPath());
        p.put("env", c.scopeEnvironment());
        p.put("queue", c.queue());
        p.put("maxAttempts", op.maxAttempts());
        p.put("deadline", ts(deadline));
        p.put("created", ts(createdAt));
        p.put("key", op.idempotencyKey());
        p.put("corr", correlationId);
        jdbc.sql("""
                INSERT INTO operation.operation (id, type, mutating, provider_type, provider_instance_id, target_id, account_id,
                    requester_id, scope_org_path, scope_environment, queue, status, attempt, max_attempts, deadline, created_at,
                    idempotency_key, correlation_id, version)
                VALUES (:id, :type, :mutating, :ptype, :pinst, :target, :account, :requester, :org, :env, :queue, 'QUEUED', 1,
                    :maxAttempts, :deadline, :created, :key, :corr, 0)""").params(p).update();
    }

    @Override
    public Optional<UUID> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("SELECT id FROM operation.operation WHERE idempotency_key = :k").param("k", idempotencyKey).query(UUID.class).optional();
    }

    @Override
    public Optional<Row> lock(UUID id) {
        return jdbc.sql("SELECT * FROM operation.operation WHERE id = :id FOR UPDATE").param("id", id).query((rs, n) -> row(rs)).optional();
    }

    @Override
    public boolean update(Operation op, long expectedVersion, Map<String, Object> providerResponse) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", op.id());
        p.put("version", expectedVersion);
        p.put("status", op.status().name());
        p.put("reason", op.statusReason());
        p.put("attempt", op.attempt());
        p.put("started", ts(op.startedAt()));
        p.put("finished", ts(op.finishedAt()));
        p.put("errCode", op.errorCode());
        p.put("errMsg", op.errorMessage() == null ? null : op.errorMessage().substring(0, Math.min(1000, op.errorMessage().length())));
        p.put("resp", providerResponse == null ? null : Json.write(providerResponse));
        Operation.Verification v = op.verification();
        p.put("vmode", v == null ? null : v.mode());
        p.put("vat", v == null ? null : ts(v.verifiedAt()));
        p.put("vsum", v == null ? null : v.summary());
        return jdbc.sql("""
                UPDATE operation.operation SET status = :status, status_reason = :reason, attempt = :attempt, started_at = :started,
                    finished_at = :finished, error_code = :errCode, error_message = :errMsg,
                    provider_response = COALESCE(CAST(:resp AS jsonb), provider_response),
                    verification_mode = :vmode, verified_at = :vat, verification_summary = :vsum,
                    progress = CASE WHEN :status IN ('SUCCESS','FAILED','TIMEOUT','CANCELLED','PARTIAL','UNKNOWN') THEN 100 ELSE progress END,
                    version = version + 1
                WHERE id = :id AND version = :version""").params(p).update() == 1;
    }

    @Override
    public boolean markProcessed(String consumer, UUID messageId, Instant at) {
        return jdbc.sql("""
                INSERT INTO operation.processed_message (consumer, message_id, processed_at) VALUES (:c, :m, :at)
                ON CONFLICT DO NOTHING""").param("c", consumer).param("m", messageId).param("at", ts(at)).update() == 1;
    }

    @Override
    public List<UUID> findOverdue(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id FROM operation.operation
                WHERE status IN ('QUEUED','RUNNING') AND deadline IS NOT NULL AND deadline < :now
                ORDER BY deadline LIMIT :limit""").param("now", ts(now)).param("limit", limit).query(UUID.class).list();
    }

    @Override
    public boolean hasInFlightMutating(UUID accountId) {
        return jdbc.sql("""
                SELECT count(*) FROM operation.operation
                WHERE account_id = :a AND mutating AND status IN ('QUEUED','RUNNING','UNKNOWN','PARTIAL')""")
                .param("a", accountId).query(Long.class).single() > 0;
    }

    static Row row(ResultSet rs) throws SQLException {
        String vmode = rs.getString("verification_mode");
        Instant vat = instant(rs, "verified_at");
        Operation.Verification v = vmode == null || vat == null ? null
                : new Operation.Verification(vmode, vat, String.valueOf(rs.getString("verification_summary")));
        Operation op = new Operation(uuid(rs, "id"), rs.getString("type"), rs.getBoolean("mutating"), rs.getString("idempotency_key"),
                OperationStatus.valueOf(rs.getString("status")), rs.getString("status_reason"), rs.getInt("attempt"),
                rs.getInt("max_attempts"), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getString("error_code"),
                rs.getString("error_message"), v, rs.getLong("version"));
        return new Row(op, rs.getString("provider_type"), uuid(rs, "provider_instance_id"), uuid(rs, "target_id"),
                uuid(rs, "account_id"), instant(rs, "deadline"));
    }
}
