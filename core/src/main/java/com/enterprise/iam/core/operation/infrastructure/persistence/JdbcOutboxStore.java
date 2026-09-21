package com.enterprise.iam.core.operation.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.operation.api.OutboxMessage;
import com.enterprise.iam.core.operation.application.OutboxStore;
import com.enterprise.iam.kernel.Json;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OutboxMessage m, Instant now) {
        jdbc.sql("""
                INSERT INTO operation.outbox_message (id, destination, aggregate_type, aggregate_id, payload, headers, status,
                    attempts, next_attempt_at, created_at)
                VALUES (:id, :dest, :aggType, :aggId, CAST(:payload AS jsonb), CAST(:headers AS jsonb), 'PENDING', 0, :now, :now)""")
                .param("id", m.id()).param("dest", m.destination()).param("aggType", m.aggregateType())
                .param("aggId", m.aggregateId()).param("payload", Json.write(m.payload())).param("headers", Json.write(m.headers()))
                .param("now", ts(now)).update();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<OutboxMessage> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                UPDATE operation.outbox_message m SET next_attempt_at = :lease
                WHERE m.id IN (SELECT id FROM operation.outbox_message
                               WHERE status = 'PENDING' AND next_attempt_at <= :now
                               ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING m.id, m.destination, m.aggregate_type, m.aggregate_id, m.payload::text AS payload_text,
                          m.headers::text AS headers_text, m.attempts, m.created_at""")
                .param("lease", ts(leaseUntil)).param("now", ts(now)).param("limit", limit)
                .query((rs, n) -> new OutboxMessage(uuid(rs, "id"), rs.getString("destination"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), Json.parseObject(rs.getString("payload_text")),
                        (Map<String, String>) (Map<String, ?>) Json.parseObject(rs.getString("headers_text")),
                        rs.getInt("attempts"), instant(rs, "created_at")))
                .list();
    }

    @Override
    public void markPublished(UUID id, Instant now) {
        jdbc.sql("UPDATE operation.outbox_message SET status = 'PUBLISHED', published_at = :now, last_error = NULL WHERE id = :id")
                .param("now", ts(now)).param("id", id).update();
    }

    @Override
    public void markFailed(UUID id, int attempts, Instant nextAttemptAt, String error, boolean parked) {
        jdbc.sql("""
                UPDATE operation.outbox_message SET attempts = :attempts, next_attempt_at = :next, last_error = :err,
                    status = CASE WHEN :parked THEN 'PARKED' ELSE 'PENDING' END
                WHERE id = :id""")
                .param("attempts", attempts).param("next", ts(nextAttemptAt)).param("err", error).param("parked", parked)
                .param("id", id).update();
    }

    @Override
    public void release(UUID id, Instant nextAttemptAt) {
        jdbc.sql("UPDATE operation.outbox_message SET next_attempt_at = :next WHERE id = :id AND status = 'PENDING'")
                .param("next", ts(nextAttemptAt)).param("id", id).update();
    }

    @Override
    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM operation.outbox_message WHERE status = 'PENDING'").query(Long.class).single();
    }
}
