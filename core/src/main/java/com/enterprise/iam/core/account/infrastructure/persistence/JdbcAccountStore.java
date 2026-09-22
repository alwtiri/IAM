package com.enterprise.iam.core.account.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.json;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.stringMap;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.account.api.AccountFindingView;
import com.enterprise.iam.core.account.api.DiscoveryRunView;
import com.enterprise.iam.core.account.application.AccountStore;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.FindingRules;
import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcAccountStore implements AccountStore {

    private static final ScopeSql.Columns SCOPE = new ScopeSql.Columns("ou.path", "t.environment", "p.type", "a.provider_instance_id", "a.target_id");
    private static final String FROM = """
             FROM account.account a
             JOIN target.target t ON t.id = a.target_id
             JOIN organization.org_unit ou ON ou.id = t.owner_org_unit_id
             JOIN provider.provider_instance p ON p.id = a.provider_instance_id""";
    private static final String SELECT = "SELECT a.*, a.attributes::text AS attributes_text, t.name AS target_name, ou.path AS org_path, "
            + "t.environment AS target_env, p.type AS provider_type" + FROM;

    private final JdbcClient jdbc;

    public JdbcAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public void insert(Account a) {
        jdbc.sql("""
                INSERT INTO account.account (id, target_id, provider_instance_id, native_id, name, display_name, account_type, privileged,
                    privilege_reason, owner_identity_id, linked_identity_id, governance_state, native_status, source, attributes,
                    last_seen_at, last_login_at, password_last_set_at, created_at, updated_at, version)
                VALUES (:id, :target, :instance, :nativeId, :name, :displayName, :type, :privileged, :privReason, :owner, :linked,
                    :gov, :status, :source, CAST(:attrs AS jsonb), :lastSeen, :lastLogin, :pwdSet, now(), now(), 0)""")
                .params(params(a)).update();
    }

    @Override
    public boolean update(Account a, long expectedVersion) {
        Map<String, Object> p = params(a);
        p.put("version", expectedVersion);
        return jdbc.sql("""
                UPDATE account.account SET name = :name, display_name = :displayName, account_type = :type, privileged = :privileged,
                    privilege_reason = :privReason, owner_identity_id = :owner, linked_identity_id = :linked, governance_state = :gov,
                    native_status = :status, attributes = CAST(:attrs AS jsonb), last_seen_at = :lastSeen, last_login_at = :lastLogin,
                    password_last_set_at = :pwdSet, updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version""").params(p).update() == 1;
    }

    private static Map<String, Object> params(Account a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.id());
        m.put("target", a.targetId());
        m.put("instance", a.providerInstanceId());
        m.put("nativeId", a.nativeId());
        m.put("name", a.name());
        m.put("displayName", a.displayName());
        m.put("type", a.type().name());
        m.put("privileged", a.privileged());
        m.put("privReason", a.privilegeReason());
        m.put("owner", a.ownerIdentityId());
        m.put("linked", a.linkedIdentityId());
        m.put("gov", a.governanceState().name());
        m.put("status", a.nativeStatus().name());
        m.put("source", a.source().name());
        m.put("attrs", json(a.attributes()));
        m.put("lastSeen", ts(a.lastSeenAt()));
        m.put("lastLogin", ts(a.lastLoginAt()));
        m.put("pwdSet", ts(a.passwordLastSetAt()));
        return m;
    }

    @Override
    public Optional<Scoped> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query((rs, n) -> scoped(rs)).optional();
    }

    @Override
    public Map<String, Account> findByNativeIds(UUID targetId, UUID providerInstanceId, Collection<String> nativeIds) {
        if (nativeIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(SELECT + " WHERE a.target_id = :t AND a.provider_instance_id = :p AND a.native_id IN (:ids)")
                .param("t", targetId).param("p", providerInstanceId).param("ids", List.copyOf(nativeIds))
                .query((rs, n) -> account(rs)).list().stream().collect(Collectors.toMap(Account::nativeId, Function.identity()));
    }

    @Override
    public List<Account> findNotSeenSince(UUID targetId, UUID providerInstanceId, Instant before) {
        return jdbc.sql(SELECT + " WHERE a.target_id = :t AND a.provider_instance_id = :p AND a.source = 'DISCOVERY'"
                        + " AND (a.last_seen_at IS NULL OR a.last_seen_at < :before)")
                .param("t", targetId).param("p", providerInstanceId).param("before", ts(before))
                .query((rs, n) -> account(rs)).list();
    }

    @Override
    public List<Scoped> list(ScopeFilter filter, ListFilter f, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE ").append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (f.targetId() != null) {
            sql.append(" AND a.target_id = :target");
            params.put("target", f.targetId());
        }
        if (f.governanceState() != null) {
            sql.append(" AND a.governance_state = :gov");
            params.put("gov", f.governanceState());
        }
        if (f.privileged() != null) {
            sql.append(" AND a.privileged = :priv");
            params.put("priv", f.privileged());
        }
        if (f.search() != null && !f.search().isBlank()) {
            sql.append(" AND (lower(a.name) LIKE :q ESCAPE '\\' OR lower(coalesce(a.display_name, '')) LIKE :q ESCAPE '\\')");
            params.put("q", "%" + escapeLike(f.search().toLowerCase(java.util.Locale.ROOT)) + "%");
        }
        if (f.findingType() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM account.account_finding af WHERE af.account_id = a.id AND af.resolved_at IS NULL AND af.type = :ftype)");
            params.put("ftype", f.findingType());
        }
        if (page.after() != null) {
            sql.append(" AND a.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY a.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> scoped(rs)).list();
    }

    @Override
    public Optional<Binding> binding(UUID targetId, UUID providerInstanceId) {
        return jdbc.sql("""
                SELECT t.id AS target_id, p.id AS instance_id, ou.path AS org_path, t.environment, p.type
                FROM target.target t
                JOIN organization.org_unit ou ON ou.id = t.owner_org_unit_id
                JOIN provider.target_binding b ON b.target_id = t.id
                JOIN provider.provider_instance p ON p.id = b.provider_instance_id
                WHERE t.id = :t AND p.id = :p
                LIMIT 1""")
                .param("t", targetId).param("p", providerInstanceId)
                .query((rs, n) -> new Binding(uuid(rs, "target_id"), uuid(rs, "instance_id"), rs.getString("org_path"),
                        rs.getString("environment"), rs.getString("type")))
                .optional();
    }

    private static Scoped scoped(ResultSet rs) throws SQLException {
        return new Scoped(account(rs), rs.getString("target_name"), rs.getString("org_path"), rs.getString("target_env"),
                rs.getString("provider_type"));
    }

    static Account account(ResultSet rs) throws SQLException {
        return new Account(uuid(rs, "id"), uuid(rs, "target_id"), uuid(rs, "provider_instance_id"), rs.getString("native_id"),
                rs.getString("name"), rs.getString("display_name"), Account.Type.valueOf(rs.getString("account_type")),
                rs.getBoolean("privileged"), rs.getString("privilege_reason"), uuid(rs, "owner_identity_id"),
                uuid(rs, "linked_identity_id"), Account.GovernanceState.valueOf(rs.getString("governance_state")),
                Account.NativeStatus.valueOf(rs.getString("native_status")), Account.Source.valueOf(rs.getString("source")),
                stringMap(rs, "attributes_text"), instant(rs, "last_seen_at"), instant(rs, "last_login_at"),
                instant(rs, "password_last_set_at"), rs.getLong("version"));
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ------------------------------------------------------------------ findings

    private static final String FINDING_SELECT = """
            SELECT f.*, f.details::text AS details_text, a.name AS account_name, a.target_id, a.provider_instance_id,
                   t.name AS target_name, ou.path AS org_path, t.environment AS target_env, p.type AS provider_type
            FROM account.account_finding f
            JOIN account.account a ON a.id = f.account_id
            JOIN target.target t ON t.id = a.target_id
            JOIN organization.org_unit ou ON ou.id = t.owner_org_unit_id
            JOIN provider.provider_instance p ON p.id = a.provider_instance_id""";

    @Override
    public Map<FindingRules.Type, UUID> openFindings(UUID accountId) {
        Map<FindingRules.Type, UUID> out = new EnumMap<>(FindingRules.Type.class);
        jdbc.sql("SELECT id, type FROM account.account_finding WHERE account_id = :a AND resolved_at IS NULL")
                .param("a", accountId)
                .query((rs, n) -> Map.entry(FindingRules.Type.valueOf(rs.getString("type")), uuid(rs, "id")))
                .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public void openFinding(UUID id, UUID accountId, FindingRules.Type type, FindingRules.Severity severity, Instant at, Map<String, String> details) {
        jdbc.sql("""
                INSERT INTO account.account_finding (id, account_id, type, severity, detected_at, details)
                VALUES (:id, :a, :type, :sev, :at, CAST(:details AS jsonb))
                ON CONFLICT (account_id, type) WHERE resolved_at IS NULL DO NOTHING""")
                .param("id", id).param("a", accountId).param("type", type.name()).param("sev", severity.name())
                .param("at", ts(at)).param("details", json(details)).update();
    }

    @Override
    public void autoResolveFindings(UUID accountId, Set<FindingRules.Type> types, Instant at) {
        if (types.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE account.account_finding SET resolved_at = :at, resolution = 'AUTO: condition no longer present'
                WHERE account_id = :a AND resolved_at IS NULL AND type IN (:types)""")
                .param("at", ts(at)).param("a", accountId).param("types", types.stream().map(Enum::name).toList()).update();
    }

    @Override
    public Optional<FindingScoped> findFinding(UUID findingId) {
        return jdbc.sql(FINDING_SELECT + " WHERE f.id = :id").param("id", findingId).query((rs, n) -> finding(rs)).optional();
    }

    @Override
    public boolean resolveFinding(UUID findingId, UUID resolvedBy, String resolution, Instant at) {
        return jdbc.sql("""
                UPDATE account.account_finding SET resolved_at = :at, resolved_by = :by, resolution = :res
                WHERE id = :id AND resolved_at IS NULL""")
                .param("at", ts(at)).param("by", resolvedBy).param("res", resolution).param("id", findingId).update() == 1;
    }

    @Override
    public List<FindingScoped> listFindings(ScopeFilter filter, String type, String severity, boolean openOnly, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(FINDING_SELECT).append(" WHERE ").append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (openOnly) {
            sql.append(" AND f.resolved_at IS NULL");
        }
        if (type != null) {
            sql.append(" AND f.type = :type");
            params.put("type", type);
        }
        if (severity != null) {
            sql.append(" AND f.severity = :sev");
            params.put("sev", severity);
        }
        if (page.after() != null) {
            sql.append(" AND f.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY f.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> finding(rs)).list();
    }

    private static FindingScoped finding(ResultSet rs) throws SQLException {
        AccountFindingView v = new AccountFindingView(uuid(rs, "id"), uuid(rs, "account_id"), rs.getString("account_name"),
                uuid(rs, "target_id"), rs.getString("target_name"), rs.getString("type"), rs.getString("severity"),
                instant(rs, "detected_at"), instant(rs, "resolved_at"), rs.getString("resolution"), stringMap(rs, "details_text"));
        return new FindingScoped(v, rs.getString("org_path"), rs.getString("target_env"), rs.getString("provider_type"),
                uuid(rs, "provider_instance_id"));
    }

    // ------------------------------------------------------------------ discovery runs

    @Override
    public void insertRun(UUID id, UUID providerInstanceId, UUID targetId, UUID operationId, Instant startedAt) {
        jdbc.sql("""
                INSERT INTO account.discovery_run (id, provider_instance_id, target_id, operation_id, status, started_at)
                VALUES (:id, :p, :t, :op, 'RUNNING', :at)""")
                .param("id", id).param("p", providerInstanceId).param("t", targetId).param("op", operationId).param("at", ts(startedAt))
                .update();
    }

    @Override
    public Optional<DiscoveryRunView> findRun(UUID id) {
        return jdbc.sql("SELECT * FROM account.discovery_run WHERE id = :id").param("id", id).query((rs, n) -> run(rs)).optional();
    }

    @Override
    public void addRunCounts(UUID id, int seen, int created, int groups) {
        jdbc.sql("""
                UPDATE account.discovery_run SET accounts_seen = accounts_seen + :seen, accounts_new = accounts_new + :created,
                    groups_seen = groups_seen + :groups WHERE id = :id""")
                .param("seen", seen).param("created", created).param("groups", groups).param("id", id).update();
    }

    @Override
    public void finishRun(UUID id, String status, int removed, String errorMessage, Instant at) {
        jdbc.sql("""
                UPDATE account.discovery_run SET status = :status, accounts_removed = :removed, error_message = :err, finished_at = :at
                WHERE id = :id AND status = 'RUNNING'""")
                .param("status", status).param("removed", removed).param("err", errorMessage).param("at", ts(at)).param("id", id)
                .update();
    }

    @Override
    public boolean hasCompletedRun(UUID targetId, UUID providerInstanceId, UUID exceptRunId) {
        return jdbc.sql("""
                SELECT count(*) FROM account.discovery_run
                WHERE target_id = :t AND provider_instance_id = :p AND status = 'COMPLETED' AND (CAST(:except AS uuid) IS NULL OR id <> :except)""")
                .param("t", targetId).param("p", providerInstanceId).param("except", exceptRunId).query(Long.class).single() > 0;
    }

    @Override
    public List<DiscoveryRunView> runs(UUID targetId, int limit) {
        return jdbc.sql("SELECT * FROM account.discovery_run WHERE target_id = :t ORDER BY started_at DESC LIMIT :limit")
                .param("t", targetId).param("limit", limit).query((rs, n) -> run(rs)).list();
    }

    private static DiscoveryRunView run(ResultSet rs) throws SQLException {
        return new DiscoveryRunView(uuid(rs, "id"), uuid(rs, "provider_instance_id"), uuid(rs, "target_id"), uuid(rs, "operation_id"),
                rs.getString("status"), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("accounts_seen"),
                rs.getInt("accounts_new"), rs.getInt("accounts_removed"), rs.getInt("groups_seen"), rs.getString("error_message"));
    }
}
