package com.enterprise.iam.core.operation.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.operation.api.OperationView;
import com.enterprise.iam.core.operation.application.OperationQueryService;
import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcOperationReader implements OperationQueryService.OperationReader {

    private static final ScopeSql.Columns SCOPE = new ScopeSql.Columns("o.scope_org_path", "o.scope_environment",
            "o.provider_type", "o.provider_instance_id", "o.target_id");

    private final JdbcClient jdbc;

    public JdbcOperationReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OperationView> list(ScopeFilter filter, String status, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT o.* FROM operation.operation o WHERE ")
                .append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (status != null) {
            sql.append(" AND o.status = :status");
            params.put("status", status);
        }
        if (page.after() != null) {
            sql.append(" AND o.id < :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY o.id DESC LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query(JdbcOperationReader::view).list();
    }

    @Override
    public Optional<OperationQueryService.ScopedOperation> find(UUID id) {
        return jdbc.sql("SELECT o.* FROM operation.operation o WHERE o.id = :id").param("id", id)
                .query((rs, n) -> new OperationQueryService.ScopedOperation(view(rs, n),
                        new ResourceScope(rs.getString("scope_org_path"), rs.getString("scope_environment"),
                                rs.getString("provider_type"), uuid(rs, "provider_instance_id"), uuid(rs, "target_id"))))
                .optional();
    }

    static OperationView view(ResultSet rs, int n) throws SQLException {
        return new OperationView(uuid(rs, "id"), rs.getString("type"), uuid(rs, "provider_instance_id"), uuid(rs, "target_id"),
                uuid(rs, "requester_id"), uuid(rs, "request_id"), rs.getString("status"), rs.getString("status_reason"),
                rs.getInt("progress"), rs.getInt("attempt"), rs.getInt("max_attempts"), instant(rs, "created_at"),
                instant(rs, "started_at"), instant(rs, "finished_at"), instant(rs, "deadline"), rs.getString("error_code"),
                rs.getString("error_message"), rs.getString("verification_mode"), instant(rs, "verified_at"),
                rs.getString("verification_summary"), rs.getString("correlation_id"));
    }
}
