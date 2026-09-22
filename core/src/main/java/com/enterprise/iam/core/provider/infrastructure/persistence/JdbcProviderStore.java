package com.enterprise.iam.core.provider.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.json;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.stringMap;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.provider.api.ProviderBindingView;
import com.enterprise.iam.core.provider.api.ProviderInstanceView;
import com.enterprise.iam.core.provider.application.ProviderStore;
import com.enterprise.iam.core.provider.domain.ProviderInstance;
import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcProviderStore implements ProviderStore {

    private static final ScopeSql.Columns SCOPE = new ScopeSql.Columns(null, null, "p.type", "p.id", null);
    private static final String SELECT = "SELECT p.*, p.settings::text AS settings_text FROM provider.provider_instance p";

    private final JdbcClient jdbc;

    public JdbcProviderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ProviderInstance p) {
        jdbc.sql("""
                INSERT INTO provider.provider_instance (id, type, name, endpoint, settings, credential_secret_ref, enabled, health,
                    circuit_state, created_at, updated_at, version)
                VALUES (:id, :type, :name, :endpoint, CAST(:settings AS jsonb), :ref, :enabled, 'UNKNOWN', 'CLOSED', now(), now(), 0)""")
                .param("id", p.id()).param("type", p.type().value()).param("name", p.name()).param("endpoint", p.endpoint())
                .param("settings", json(p.settings())).param("ref", p.credentialSecretRef()).param("enabled", p.enabled()).update();
    }

    @Override
    public boolean setEnabled(UUID id, boolean enabled, long expectedVersion) {
        return jdbc.sql("UPDATE provider.provider_instance SET enabled = :e, updated_at = now(), version = version + 1 WHERE id = :id AND version = :v")
                .param("e", enabled).param("id", id).param("v", expectedVersion).update() == 1;
    }

    @Override
    public Optional<ProviderInstance> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id)
                .query((rs, n) -> new ProviderInstance(uuid(rs, "id"), ProviderTypeId.of(rs.getString("type")), rs.getString("name"),
                        rs.getString("endpoint"), stringMap(rs, "settings_text"), rs.getString("credential_secret_ref"),
                        rs.getBoolean("enabled"), rs.getLong("version")))
                .optional();
    }

    @Override
    public Optional<ProviderInstanceView> view(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(JdbcProviderStore::view).optional();
    }

    @Override
    public boolean nameExists(String name) {
        return jdbc.sql("SELECT count(*) FROM provider.provider_instance WHERE lower(name) = lower(:n)").param("n", name)
                .query(Long.class).single() > 0;
    }

    @Override
    public List<ProviderInstanceView> list(ScopeFilter filter, String type, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ").append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (type != null) {
            sql.append(" AND p.type = :type");
            params.put("type", type);
        }
        if (page.after() != null) {
            sql.append(" AND p.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY p.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query(JdbcProviderStore::view).list();
    }

    @Override
    public boolean bind(UUID targetId, UUID providerInstanceId, String channel) {
        return jdbc.sql("""
                INSERT INTO provider.target_binding (target_id, provider_instance_id, channel) VALUES (:t, :p, :c)
                ON CONFLICT DO NOTHING""").param("t", targetId).param("p", providerInstanceId).param("c", channel).update() == 1;
    }

    @Override
    public boolean unbind(UUID targetId, UUID providerInstanceId) {
        return jdbc.sql("DELETE FROM provider.target_binding WHERE target_id = :t AND provider_instance_id = :p")
                .param("t", targetId).param("p", providerInstanceId).update() > 0;
    }

    @Override
    public List<ProviderBindingView> allBindings() {
        return jdbc.sql("""
                SELECT b.target_id, b.provider_instance_id, b.channel, p.type, p.name
                FROM provider.target_binding b JOIN provider.provider_instance p ON p.id = b.provider_instance_id
                WHERE p.enabled ORDER BY b.target_id""")
                .query((rs, n) -> new ProviderBindingView(uuid(rs, "target_id"), uuid(rs, "provider_instance_id"), rs.getString("type"),
                        rs.getString("name"), rs.getString("channel")))
                .list();
    }

    @Override
    public List<ProviderBindingView> bindings(UUID targetId) {
        return jdbc.sql("""
                SELECT b.target_id, b.provider_instance_id, b.channel, p.type, p.name
                FROM provider.target_binding b JOIN provider.provider_instance p ON p.id = b.provider_instance_id
                WHERE b.target_id = :t ORDER BY p.name, b.channel""").param("t", targetId)
                .query((rs, n) -> new ProviderBindingView(uuid(rs, "target_id"), uuid(rs, "provider_instance_id"), rs.getString("type"),
                        rs.getString("name"), rs.getString("channel")))
                .list();
    }

    @Override
    public boolean isBound(UUID targetId, UUID providerInstanceId) {
        return jdbc.sql("SELECT count(*) FROM provider.target_binding WHERE target_id = :t AND provider_instance_id = :p")
                .param("t", targetId).param("p", providerInstanceId).query(Long.class).single() > 0;
    }

    static ProviderInstanceView view(ResultSet rs, int n) throws SQLException {
        return new ProviderInstanceView(uuid(rs, "id"), rs.getString("type"), rs.getString("name"), rs.getString("endpoint"),
                stringMap(rs, "settings_text"), rs.getString("credential_secret_ref") != null, rs.getBoolean("enabled"),
                rs.getString("health"), rs.getString("circuit_state"), instant(rs, "last_health_at"), rs.getString("failure_reason"),
                rs.getLong("version"));
    }
}
