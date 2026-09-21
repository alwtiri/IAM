package com.enterprise.iam.core.target.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.target.application.TargetStore;
import com.enterprise.iam.core.target.domain.Target;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcTargetStore implements TargetStore {

    private static final ScopeSql.Columns SCOPE = new ScopeSql.Columns("ou.path", "t.environment", null, null, "t.id");
    private static final String SELECT = "SELECT t.*, ou.path AS org_path FROM target.target t JOIN organization.org_unit ou ON ou.id = t.owner_org_unit_id";

    private final JdbcClient jdbc;

    public JdbcTargetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Target t) {
        jdbc.sql("""
                INSERT INTO target.target (id, name, hostname, ip_address, dns_name, type, platform, operating_system, environment,
                    criticality, classification, owner_org_unit_id, owner_identity_id, technical_owner_identity_id,
                    business_owner_identity_id, location_id, tags, status, created_at, updated_at, version)
                VALUES (:id, :name, :hostname, CAST(:ip AS inet), :dns, :type, :platform, :os, :env, :crit, :class, :org, :owner,
                    :tech, :biz, :loc, CAST(:tags AS text[]), :status, now(), now(), 0)""")
                .params(params(t)).update();
    }

    @Override
    public boolean update(Target t, long expectedVersion) {
        Map<String, Object> p = params(t);
        p.put("version", expectedVersion);
        return jdbc.sql("""
                UPDATE target.target SET name = :name, hostname = :hostname, ip_address = CAST(:ip AS inet), dns_name = :dns,
                    type = :type, platform = :platform, operating_system = :os, environment = :env, criticality = :crit,
                    classification = :class, owner_org_unit_id = :org, owner_identity_id = :owner,
                    technical_owner_identity_id = :tech, business_owner_identity_id = :biz, location_id = :loc,
                    tags = CAST(:tags AS text[]), status = :status, updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version""").params(p).update() == 1;
    }

    private static Map<String, Object> params(Target t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("hostname", t.hostname());
        m.put("ip", t.ipAddress());
        m.put("dns", t.dnsName());
        m.put("type", t.type().name());
        m.put("platform", t.platform());
        m.put("os", t.operatingSystem());
        m.put("env", t.environment());
        m.put("crit", t.criticality().name());
        m.put("class", t.classification().name());
        m.put("org", t.ownerOrgUnitId());
        m.put("owner", t.ownerIdentityId());
        m.put("tech", t.technicalOwnerIdentityId());
        m.put("biz", t.businessOwnerIdentityId());
        m.put("loc", t.locationId());
        m.put("tags", toArrayLiteral(t.tags()));
        m.put("status", t.status().name());
        return m;
    }

    /** Tags are validated against [A-Za-z0-9_.:-], so a simple PostgreSQL array literal is safe. */
    static String toArrayLiteral(List<String> tags) {
        return "{" + String.join(",", tags) + "}";
    }

    @Override
    public Optional<Scoped> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query((rs, n) -> new Scoped(map(rs), rs.getString("org_path"))).optional();
    }

    @Override
    public boolean nameExists(String name, UUID exceptId) {
        return jdbc.sql("SELECT count(*) FROM target.target WHERE lower(name) = lower(:name) AND id <> :id")
                .param("name", name).param("id", exceptId).query(Long.class).single() > 0;
    }

    @Override
    public List<Scoped> list(ScopeFilter filter, String type, String environment, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ").append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (type != null) {
            sql.append(" AND t.type = :type");
            params.put("type", type);
        }
        if (environment != null) {
            sql.append(" AND t.environment = :env");
            params.put("env", environment);
        }
        if (page.after() != null) {
            sql.append(" AND t.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY t.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new Scoped(map(rs), rs.getString("org_path"))).list();
    }

    static Target map(ResultSet rs) throws SQLException {
        Array tags = rs.getArray("tags");
        List<String> tagList = tags == null ? List.of() : Arrays.asList((String[]) tags.getArray());
        String ip = rs.getString("ip_address");
        if (ip != null && ip.contains("/")) {
            ip = ip.substring(0, ip.indexOf('/'));
        }
        return new Target(uuid(rs, "id"), rs.getString("name"), rs.getString("hostname"), ip, rs.getString("dns_name"),
                Target.Type.valueOf(rs.getString("type")), rs.getString("platform"), rs.getString("operating_system"),
                rs.getString("environment"), Target.Criticality.valueOf(rs.getString("criticality")),
                Target.Classification.valueOf(rs.getString("classification")), uuid(rs, "owner_org_unit_id"),
                uuid(rs, "owner_identity_id"), uuid(rs, "technical_owner_identity_id"), uuid(rs, "business_owner_identity_id"),
                uuid(rs, "location_id"), tagList, Target.Status.valueOf(rs.getString("status")), rs.getLong("version"));
    }
}
