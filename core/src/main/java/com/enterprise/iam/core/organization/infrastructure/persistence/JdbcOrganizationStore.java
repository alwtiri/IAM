package com.enterprise.iam.core.organization.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.organization.application.OrganizationStore;
import com.enterprise.iam.core.organization.domain.CatalogEntry;
import com.enterprise.iam.core.organization.domain.OrgUnit;
import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcOrganizationStore implements OrganizationStore {

    private static final ScopeSql.Columns SCOPE = new ScopeSql.Columns("u.path", null, null, null, null);

    private final JdbcClient jdbc;

    public JdbcOrganizationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(OrgUnit u) {
        jdbc.sql("""
                INSERT INTO organization.org_unit (id, organization_id, parent_id, kind, code, name, path, created_at, updated_at, version)
                VALUES (:id, :org, :parent, :kind, :code, :name, :path, now(), now(), 0)""")
                .param("id", u.id()).param("org", u.organizationId()).param("parent", u.parentId()).param("kind", u.kind().name())
                .param("code", u.code()).param("name", u.name()).param("path", u.path()).update();
    }

    @Override
    public boolean update(OrgUnit u, long expectedVersion) {
        return jdbc.sql("""
                UPDATE organization.org_unit SET name = :name, updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version""")
                .param("name", u.name()).param("id", u.id()).param("version", expectedVersion).update() == 1;
    }

    @Override
    public Optional<OrgUnit> find(UUID id) {
        return jdbc.sql("SELECT * FROM organization.org_unit u WHERE u.id = :id AND u.archived_at IS NULL")
                .param("id", id).query(JdbcOrganizationStore::map).optional();
    }

    @Override
    public boolean codeExists(UUID organizationId, String code) {
        return jdbc.sql("SELECT count(*) FROM organization.org_unit WHERE organization_id = :org AND code = :code")
                .param("org", organizationId).param("code", code).query(Long.class).single() > 0;
    }

    @Override
    public List<OrgUnit> list(ScopeFilter filter, UUID parentId, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT u.* FROM organization.org_unit u WHERE u.archived_at IS NULL AND ")
                .append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (parentId != null) {
            sql.append(" AND u.parent_id = :parent");
            params.put("parent", parentId);
        }
        if (page.after() != null) {
            sql.append(" AND u.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY u.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query(JdbcOrganizationStore::map).list();
    }

    @Override
    public void insert(Catalog catalog, CatalogEntry e) {
        jdbc.sql("INSERT INTO organization." + table(catalog) + " (id, code, name, detail, created_at) VALUES (:id, :code, :name, :detail, now())")
                .param("id", e.id()).param("code", e.code()).param("name", e.name()).param("detail", e.detail()).update();
    }

    @Override
    public boolean catalogCodeExists(Catalog catalog, String code) {
        return jdbc.sql("SELECT count(*) FROM organization." + table(catalog) + " WHERE code = :code").param("code", code)
                .query(Long.class).single() > 0;
    }

    @Override
    public List<CatalogEntry> listCatalog(Catalog catalog) {
        return jdbc.sql("SELECT id, code, name, detail FROM organization." + table(catalog) + " ORDER BY code")
                .query((rs, n) -> new CatalogEntry(uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getString("detail")))
                .list();
    }

    @Override
    public boolean catalogEntryExists(Catalog catalog, UUID id) {
        return id != null && jdbc.sql("SELECT count(*) FROM organization." + table(catalog) + " WHERE id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }

    /** Table names come from the enum, never from input. */
    private static String table(Catalog c) {
        return switch (c) {
            case POSITION -> "position";
            case LOCATION -> "location";
        };
    }

    static OrgUnit map(ResultSet rs, int n) throws SQLException {
        return new OrgUnit(uuid(rs, "id"), uuid(rs, "organization_id"), uuid(rs, "parent_id"), OrgUnit.Kind.valueOf(rs.getString("kind")),
                rs.getString("code"), rs.getString("name"), rs.getString("path"), rs.getLong("version"));
    }
}
