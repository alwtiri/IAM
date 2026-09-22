package com.enterprise.iam.core.audit.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.json;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.stringMap;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.audit.api.AuditSearch;
import com.enterprise.iam.core.audit.application.AuditStore;
import com.enterprise.iam.core.audit.domain.AuditEvent;
import com.enterprise.iam.core.audit.domain.ChainHead;
import com.enterprise.iam.core.audit.domain.HashChain;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Audit persistence (ADR-0008, ADR-0015). The table rejects UPDATE/DELETE/TRUNCATE by trigger. */
public class JdbcAuditStore implements AuditStore {

    private static final String COLUMNS = """
            id, chain_partition, seq, occurred_at, actor_identity_id, actor_type, action, object_type, object_id,
            target_id, source, result, reason, correlation_id, host(ip) AS ip_text, provider_instance_id,
            details::text AS details_text, prev_hash, hash""";

    private final JdbcClient jdbc;

    public JdbcAuditStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ChainHead lockHead(String partition) {
        jdbc.sql("""
                INSERT INTO audit.chain_head (chain_partition, last_seq, last_hash, updated_at)
                VALUES (:p, 0, :genesis, now()) ON CONFLICT (chain_partition) DO NOTHING""")
                .param("p", partition).param("genesis", HashChain.GENESIS).update();
        return jdbc.sql("SELECT chain_partition, last_seq, last_hash FROM audit.chain_head WHERE chain_partition = :p FOR UPDATE")
                .param("p", partition)
                .query((rs, n) -> new ChainHead(rs.getString(1), rs.getLong(2), rs.getBytes(3)))
                .single();
    }

    @Override
    public ChainHead readHead(String partition) {
        return jdbc.sql("SELECT chain_partition, last_seq, last_hash FROM audit.chain_head WHERE chain_partition = :p")
                .param("p", partition)
                .query((rs, n) -> new ChainHead(rs.getString(1), rs.getLong(2), rs.getBytes(3)))
                .optional().orElse(ChainHead.genesis(partition));
    }

    @Override
    public void insert(AuditEvent e) {
        jdbc.sql("""
                INSERT INTO audit.audit_event (id, chain_partition, seq, occurred_at, actor_identity_id, actor_type, action,
                    object_type, object_id, target_id, source, result, reason, correlation_id, ip, provider_instance_id,
                    details, prev_hash, hash, schema_version)
                VALUES (:id, :partition, :seq, :occurredAt, :actor, :actorType, :action, :objectType, :objectId, :targetId,
                    :source, :result, :reason, :correlationId, CAST(:ip AS inet), :providerInstanceId, CAST(:details AS jsonb),
                    :prevHash, :hash, 1)""")
                .param("id", e.id()).param("partition", e.chainPartition()).param("seq", e.seq())
                .param("occurredAt", ts(e.occurredAt())).param("actor", e.actorIdentityId()).param("actorType", e.actorType())
                .param("action", e.action()).param("objectType", e.objectType()).param("objectId", e.objectId())
                .param("targetId", e.targetId()).param("source", e.source()).param("result", e.result())
                .param("reason", e.reason()).param("correlationId", e.correlationId()).param("ip", e.ip())
                .param("providerInstanceId", e.providerInstanceId()).param("details", json(e.details()))
                .param("prevHash", e.prevHash()).param("hash", e.hash())
                .update();
    }

    @Override
    public void updateHead(ChainHead head) {
        int n = jdbc.sql("UPDATE audit.chain_head SET last_seq = :seq, last_hash = :hash, updated_at = now() WHERE chain_partition = :p")
                .param("seq", head.lastSeq()).param("hash", head.lastHash()).param("p", head.chainPartition()).update();
        if (n != 1) {
            throw new IllegalStateException("audit chain head missing for " + head.chainPartition());
        }
    }

    @Override
    public List<AuditEvent> search(AuditSearch f, PageRequest page) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM audit.audit_event WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        if (f.actorIdentityId() != null) {
            sql.append(" AND actor_identity_id = :actor");
            params.put("actor", f.actorIdentityId());
        }
        if (f.action() != null) {
            // comma-separated list of exact actions or prefixes ending in '*' (e.g. "credential.*,auth.access.denied")
            List<String> ors = new java.util.ArrayList<>();
            int i = 0;
            for (String part : f.action().split(",")) {
                String a = part.trim();
                if (a.isEmpty() || i >= 20) {
                    continue;
                }
                String key = "action" + i++;
                if (a.endsWith("*")) {
                    ors.add("action LIKE :" + key);
                    params.put(key, a.substring(0, a.length() - 1).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                } else {
                    ors.add("action = :" + key);
                    params.put(key, a);
                }
            }
            if (!ors.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", ors)).append(")");
            }
        }
        if (f.objectType() != null) {
            List<String> types = java.util.Arrays.stream(f.objectType().split(",")).map(String::trim).filter(x -> !x.isEmpty()).limit(20).toList();
            sql.append(" AND object_type IN (:objectTypes)");
            params.put("objectTypes", types.isEmpty() ? List.of("") : types);
        }
        if (f.objectId() != null) {
            sql.append(" AND object_id = :objectId");
            params.put("objectId", f.objectId());
        }
        if (f.correlationId() != null) {
            sql.append(" AND correlation_id = :corr");
            params.put("corr", f.correlationId());
        }
        if (f.from() != null) {
            sql.append(" AND occurred_at >= :from");
            params.put("from", ts(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND occurred_at < :to");
            params.put("to", ts(f.to()));
        }
        if (page.after() != null) {
            sql.append(" AND id < :after"); // newest first; ids are time-ordered UUIDv7
            params.put("after", page.after());
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query(JdbcAuditStore::map).list();
    }

    @Override
    public void streamPartition(String partition, Consumer<AuditEvent> consumer) {
        jdbc.sql("SELECT " + COLUMNS + " FROM audit.audit_event WHERE chain_partition = :p ORDER BY seq")
                .param("p", partition)
                .query((RowCallbackHandler) rs -> consumer.accept(map(rs, 0)));
    }

    static AuditEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new AuditEvent(uuid(rs, "id"), rs.getString("chain_partition"), rs.getLong("seq"), instant(rs, "occurred_at"),
                uuid(rs, "actor_identity_id"), rs.getString("actor_type"), rs.getString("action"), rs.getString("object_type"),
                rs.getString("object_id"), uuid(rs, "target_id"), rs.getString("source"), rs.getString("result"),
                rs.getString("reason"), rs.getString("correlation_id"), rs.getString("ip_text"), uuid(rs, "provider_instance_id"),
                stringMap(rs, "details_text"), rs.getBytes("prev_hash"), rs.getBytes("hash"));
    }
}
