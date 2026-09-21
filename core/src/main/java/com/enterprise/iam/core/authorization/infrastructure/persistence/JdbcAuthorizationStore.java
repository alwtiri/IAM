package com.enterprise.iam.core.authorization.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.authorization.application.AuthorizationStore;
import com.enterprise.iam.core.authorization.domain.AssignmentScope;
import com.enterprise.iam.core.authorization.domain.AuthorizationEngine;
import com.enterprise.iam.core.authorization.domain.Role;
import com.enterprise.iam.core.authorization.domain.RoleAssignment;
import com.enterprise.iam.core.authorization.domain.ScopeElement;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Roles, permissions, and scoped assignments. Effective grants are loaded with two set-based queries (no N+1). */
public class JdbcAuthorizationStore implements AuthorizationStore {

    private final JdbcClient jdbc;

    public JdbcAuthorizationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuthorizationEngine.EffectiveGrant> effectiveGrants(UUID identityId, Instant now) {
        List<RoleAssignment> active = jdbc.sql("""
                SELECT ra.* FROM "authorization".role_assignment ra
                JOIN identity.identity i ON i.id = ra.identity_id AND i.state = 'ACTIVE'
                WHERE ra.identity_id = :id AND ra.status = 'ACTIVE' AND ra.valid_from <= :now
                  AND (ra.valid_until IS NULL OR ra.valid_until > :now)""")
                .param("id", identityId).param("now", ts(now))
                .query((rs, n) -> assignmentWithoutScope(rs)).list();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<UUID, Set<ScopeElement>> scopes = scopesOf(active.stream().map(RoleAssignment::id).toList());
        Map<UUID, Set<String>> permissions = permissionsByRole();
        List<AuthorizationEngine.EffectiveGrant> grants = new ArrayList<>();
        for (RoleAssignment a : active) {
            Set<ScopeElement> scope = scopes.get(a.id());
            if (scope == null || scope.isEmpty()) {
                continue; // malformed row: fail closed
            }
            grants.add(new AuthorizationEngine.EffectiveGrant(permissions.getOrDefault(a.roleId(), Set.of()), new AssignmentScope(scope)));
        }
        return grants;
    }

    @Override
    public List<String> permissionCatalog() {
        return jdbc.sql("SELECT code FROM \"authorization\".permission ORDER BY code").query(String.class).list();
    }

    @Override
    public List<Role> roles() {
        Map<UUID, Set<String>> perms = permissionsByRole();
        return jdbc.sql("SELECT id, code, name, description, built_in FROM \"authorization\".role ORDER BY code")
                .query((rs, n) -> new Role(uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getString("description"),
                        rs.getBoolean("built_in"), perms.getOrDefault(uuid(rs, "id"), Set.of())))
                .list();
    }

    @Override
    public Optional<Role> role(UUID id) {
        return roles().stream().filter(r -> r.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Role> roleByCode(String code) {
        return roles().stream().filter(r -> r.code().equals(code)).findFirst();
    }

    @Override
    public void insert(RoleAssignment a) {
        jdbc.sql("""
                INSERT INTO "authorization".role_assignment (id, identity_id, role_id, source, status, scope_key, valid_from, valid_until,
                    granted_by, granted_at, revoked_by, revoked_at, reason, version)
                VALUES (:id, :identity, :role, :source, :status, :scopeKey, :from, :until, :grantedBy, :grantedAt, NULL, NULL, :reason, 0)""")
                .param("id", a.id()).param("identity", a.identityId()).param("role", a.roleId()).param("source", a.source().name())
                .param("status", a.status().name()).param("scopeKey", scopeKey(a.scope())).param("from", ts(a.validFrom()))
                .param("until", ts(a.validUntil())).param("grantedBy", a.grantedBy()).param("grantedAt", ts(a.grantedAt()))
                .param("reason", a.reason()).update();
        for (ScopeElement e : a.scope().elements()) {
            jdbc.sql("""
                    INSERT INTO "authorization".role_assignment_scope (assignment_id, element_type, element_value)
                    VALUES (:id, :type, :value)""")
                    .param("id", a.id()).param("type", e.type().name()).param("value", e.value()).update();
        }
    }

    @Override
    public boolean update(RoleAssignment a, long expectedVersion) {
        return jdbc.sql("""
                UPDATE "authorization".role_assignment SET status = :status, revoked_by = :revokedBy, revoked_at = :revokedAt,
                    reason = :reason, version = version + 1
                WHERE id = :id AND version = :version""")
                .param("status", a.status().name()).param("revokedBy", a.revokedBy()).param("revokedAt", ts(a.revokedAt()))
                .param("reason", a.reason()).param("id", a.id()).param("version", expectedVersion).update() == 1;
    }

    @Override
    public Optional<RoleAssignment> assignment(UUID id) {
        return load("SELECT * FROM \"authorization\".role_assignment WHERE id = :id", Map.of("id", id)).stream().findFirst();
    }

    @Override
    public List<RoleAssignment> assignmentsOf(UUID identityId, boolean activeOnly, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        params.put("identity", identityId);
        params.put("limit", page.limit() + 1);
        StringBuilder sql = new StringBuilder("SELECT * FROM \"authorization\".role_assignment WHERE identity_id = :identity");
        if (activeOnly) {
            sql.append(" AND status = 'ACTIVE'");
        }
        if (page.after() != null) {
            sql.append(" AND id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY id LIMIT :limit");
        return load(sql.toString(), params);
    }

    @Override
    public boolean activeDuplicateExists(RoleAssignment c) {
        return jdbc.sql("""
                SELECT count(*) FROM "authorization".role_assignment
                WHERE identity_id = :identity AND role_id = :role AND scope_key = :key AND status = 'ACTIVE'""")
                .param("identity", c.identityId()).param("role", c.roleId()).param("key", scopeKey(c.scope()))
                .query(Long.class).single() > 0;
    }

    @Override
    public long countEffectiveGlobal(UUID roleId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM "authorization".role_assignment ra
                JOIN identity.identity i ON i.id = ra.identity_id AND i.state = 'ACTIVE'
                WHERE ra.role_id = :role AND ra.status = 'ACTIVE' AND ra.scope_key = 'GLOBAL:*'
                  AND ra.valid_from <= :now AND (ra.valid_until IS NULL OR ra.valid_until > :now)""")
                .param("role", roleId).param("now", ts(now)).query(Long.class).single();
    }

    @Override
    public List<RoleAssignment> findExpired(Instant now, int limit) {
        return load("""
                SELECT * FROM "authorization".role_assignment WHERE status = 'ACTIVE' AND valid_until <= :now
                ORDER BY valid_until LIMIT :limit FOR UPDATE SKIP LOCKED""", Map.of("now", ts(now), "limit", limit));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private List<RoleAssignment> load(String sql, Map<String, ?> params) {
        List<RoleAssignment> rows = jdbc.sql(sql).params(params).query((rs, n) -> assignmentWithoutScope(rs)).list();
        if (rows.isEmpty()) {
            return rows;
        }
        Map<UUID, Set<ScopeElement>> scopes = scopesOf(rows.stream().map(RoleAssignment::id).toList());
        return rows.stream().filter(a -> scopes.containsKey(a.id()))
                .map(a -> new RoleAssignment(a.id(), a.identityId(), a.roleId(), new AssignmentScope(scopes.get(a.id())), a.source(),
                        a.status(), a.validFrom(), a.validUntil(), a.grantedBy(), a.grantedAt(), a.revokedBy(), a.revokedAt(),
                        a.reason(), a.version()))
                .toList();
    }

    private Map<UUID, Set<ScopeElement>> scopesOf(List<UUID> ids) {
        Map<UUID, Set<ScopeElement>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT assignment_id, element_type, element_value FROM \"authorization\".role_assignment_scope WHERE assignment_id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> Map.entry(uuid(rs, "assignment_id"),
                        new ScopeElement(ScopeElement.Type.valueOf(rs.getString("element_type")), rs.getString("element_value"))))
                .list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(e.getValue()));
        return out;
    }

    private Map<UUID, Set<String>> permissionsByRole() {
        Map<UUID, Set<String>> out = new HashMap<>();
        jdbc.sql("SELECT role_id, permission_code FROM \"authorization\".role_permission")
                .query((rs, n) -> Map.entry(uuid(rs, "role_id"), rs.getString("permission_code")))
                .list()
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(e.getValue()));
        return out;
    }

    /** A placeholder GLOBAL scope is used until the scope rows are attached in {@link #load}. */
    private static RoleAssignment assignmentWithoutScope(ResultSet rs) throws SQLException {
        return new RoleAssignment(uuid(rs, "id"), uuid(rs, "identity_id"), uuid(rs, "role_id"), AssignmentScope.global(),
                RoleAssignment.Source.valueOf(rs.getString("source")), RoleAssignment.Status.valueOf(rs.getString("status")),
                instant(rs, "valid_from"), instant(rs, "valid_until"), uuid(rs, "granted_by"), instant(rs, "granted_at"),
                uuid(rs, "revoked_by"), instant(rs, "revoked_at"), rs.getString("reason"), rs.getLong("version"));
    }

    /** Canonical scope description, e.g. {@code GLOBAL:*} or {@code ENVIRONMENT:TEST,ORG_UNIT_TREE:<uuid>}. */
    public static String scopeKey(AssignmentScope s) {
        return s.elements().stream().map(e -> e.type() + ":" + e.value()).sorted().collect(Collectors.joining(","));
    }
}
