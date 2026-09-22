package com.enterprise.iam.core.policy.infrastructure.persistence;

import com.enterprise.iam.core.policy.application.PolicyStore;
import com.enterprise.iam.core.policy.domain.Policy;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcPolicyStore implements PolicyStore {

    private static final String SELECT = """
            SELECT id, code, name, description, enabled, effect, request_type, role_codes, identity_types, approvals,
                   require_justification, max_duration_days, version FROM policy.policy""";

    private final JdbcClient jdbc;

    public JdbcPolicyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Policy> all() {
        return jdbc.sql(SELECT + " ORDER BY code").query(JdbcPolicyStore::map).list();
    }

    @Override
    public Optional<Policy> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(JdbcPolicyStore::map).optional();
    }

    @Override
    public boolean setEnabled(UUID id, boolean enabled, long expectedVersion) {
        return jdbc.sql("UPDATE policy.policy SET enabled = :e, version = version + 1, updated_at = now() WHERE id = :id AND version = :v")
                .param("e", enabled).param("id", id).param("v", expectedVersion).update() == 1;
    }

    private static Policy map(ResultSet rs, int n) throws SQLException {
        Object max = rs.getObject("max_duration_days");
        return new Policy(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("enabled"), rs.getString("effect"), rs.getString("request_type"), list(rs.getArray("role_codes")),
                list(rs.getArray("identity_types")), list(rs.getArray("approvals")), rs.getBoolean("require_justification"),
                max == null ? null : ((Number) max).intValue(), rs.getLong("version"));
    }

    private static List<String> list(Array a) throws SQLException {
        return a == null ? null : Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
    }
}
