package com.enterprise.iam.core.identity.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.date;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.identity.application.IdentityStore;
import com.enterprise.iam.core.identity.domain.Identity;
import com.enterprise.iam.core.identity.domain.IdentityState;
import com.enterprise.iam.core.identity.domain.IdentityType;
import com.enterprise.iam.core.identity.domain.Person;
import com.enterprise.iam.core.shared.api.jdbc.ScopeSql;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Persons, identities, platform users. Scope uses the org unit path of the person, joined from the organization
 * schema (identity may depend on organization per MODULE-BOUNDARIES).
 */
public class JdbcIdentityStore implements IdentityStore {

    private static final ScopeSql.Columns SCOPE = new ScopeSql.Columns("ou.path", null, null, null, null);
    private static final String PERSON_FROM = " FROM identity.person p LEFT JOIN organization.org_unit ou ON ou.id = p.org_unit_id ";
    private static final String IDENTITY_SELECT = """
            SELECT i.*, p.display_name, ou.path AS org_path,
                   EXISTS (SELECT 1 FROM identity.platform_user pu WHERE pu.identity_id = i.id) AS has_platform_user
            FROM identity.identity i JOIN identity.person p ON p.id = i.person_id
            LEFT JOIN organization.org_unit ou ON ou.id = p.org_unit_id""";

    private final JdbcClient jdbc;

    public JdbcIdentityStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------------------------------------ persons

    @Override
    public void insert(Person p) {
        jdbc.sql("""
                INSERT INTO identity.person (id, org_unit_id, employee_id, given_name, family_name, display_name, position_id,
                    location_id, manager_person_id, employment_status, start_date, end_date, email, phone, created_at, updated_at, version)
                VALUES (:id, :org, :emp, :given, :family, :display, :pos, :loc, :mgr, :status, :start, :end, :email, :phone, now(), now(), 0)""")
                .params(personParams(p)).update();
    }

    @Override
    public boolean update(Person p, long expectedVersion) {
        Map<String, Object> params = personParams(p);
        params.put("version", expectedVersion);
        return jdbc.sql("""
                UPDATE identity.person SET org_unit_id = :org, employee_id = :emp, given_name = :given, family_name = :family,
                    display_name = :display, position_id = :pos, location_id = :loc, manager_person_id = :mgr,
                    employment_status = :status, start_date = :start, end_date = :end, email = :email, phone = :phone,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND version = :version""").params(params).update() == 1;
    }

    private static Map<String, Object> personParams(Person p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.id());
        m.put("org", p.orgUnitId());
        m.put("emp", p.employeeId());
        m.put("given", p.givenName());
        m.put("family", p.familyName());
        m.put("display", p.displayName());
        m.put("pos", p.positionId());
        m.put("loc", p.locationId());
        m.put("mgr", p.managerPersonId());
        m.put("status", p.employmentStatus().name());
        m.put("start", p.startDate());
        m.put("end", p.endDate());
        m.put("email", p.email());
        m.put("phone", p.phone());
        return m;
    }

    @Override
    public Optional<Scoped<Person>> findPerson(UUID id) {
        return jdbc.sql("SELECT p.*, ou.path AS org_path" + PERSON_FROM + "WHERE p.id = :id AND p.archived_at IS NULL")
                .param("id", id).query((rs, n) -> new Scoped<>(person(rs), rs.getString("org_path"), rs.getString("display_name"), false))
                .optional();
    }

    @Override
    public Optional<UUID> managerOf(UUID personId) {
        return jdbc.sql("SELECT manager_person_id FROM identity.person WHERE id = :id").param("id", personId)
                .query((rs, n) -> Optional.ofNullable(uuid(rs, "manager_person_id"))).optional().flatMap(o -> o);
    }

    @Override
    public Optional<UUID> activeIdentityOfPerson(UUID personId) {
        return jdbc.sql("SELECT id FROM identity.identity WHERE person_id = :p AND state = 'ACTIVE' ORDER BY valid_from, id LIMIT 1")
                .param("p", personId).query(UUID.class).optional();
    }

    @Override
    public boolean employeeIdExists(String employeeId, UUID exceptPersonId) {
        return jdbc.sql("SELECT count(*) FROM identity.person WHERE employee_id = :emp AND id <> :id")
                .param("emp", employeeId).param("id", exceptPersonId).query(Long.class).single() > 0;
    }

    @Override
    public List<Scoped<Person>> listPersons(ScopeFilter filter, String search, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder("SELECT p.*, ou.path AS org_path").append(PERSON_FROM)
                .append("WHERE p.archived_at IS NULL AND ").append(ScopeSql.predicate(filter, SCOPE, params, "s_"))
                .append(" AND p.id <> '00000000-0000-7000-8000-000000000002'");
        if (search != null && !search.isBlank()) {
            sql.append(" AND (lower(p.display_name) LIKE :q OR lower(coalesce(p.email, '')) LIKE :q OR p.employee_id = :exact)");
            params.put("q", "%" + search.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            params.put("exact", search);
        }
        if (page.after() != null) {
            sql.append(" AND p.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY p.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new Scoped<>(person(rs), rs.getString("org_path"), rs.getString("display_name"), false)).list();
    }

    // ------------------------------------------------------------------------------------------------ identities

    @Override
    public void insert(Identity i) {
        jdbc.sql("""
                INSERT INTO identity.identity (id, person_id, type, username, state, state_reason, valid_from, valid_until,
                    created_at, updated_at, version)
                VALUES (:id, :person, :type, :username, :state, :reason, :from, :until, now(), now(), 0)""")
                .param("id", i.id()).param("person", i.personId()).param("type", i.type().name()).param("username", i.username())
                .param("state", i.state().name()).param("reason", i.stateReason()).param("from", ts(i.validFrom()))
                .param("until", ts(i.validUntil())).update();
    }

    @Override
    public boolean update(Identity i, long expectedVersion) {
        return jdbc.sql("""
                UPDATE identity.identity SET state = :state, state_reason = :reason, valid_until = :until, updated_at = now(),
                    version = version + 1
                WHERE id = :id AND version = :version""")
                .param("state", i.state().name()).param("reason", i.stateReason()).param("until", ts(i.validUntil()))
                .param("id", i.id()).param("version", expectedVersion).update() == 1;
    }

    @Override
    public Optional<Scoped<Identity>> findIdentity(UUID id) {
        return jdbc.sql(IDENTITY_SELECT + " WHERE i.id = :id").param("id", id)
                .query((rs, n) -> new Scoped<>(identity(rs), rs.getString("org_path"), rs.getString("display_name"),
                        rs.getBoolean("has_platform_user")))
                .optional();
    }

    @Override
    public boolean usernameExists(String username) {
        return jdbc.sql("SELECT count(*) FROM identity.identity WHERE username = :u").param("u", username).query(Long.class).single() > 0;
    }

    @Override
    public Optional<UUID> identityIdByUsername(String username) {
        return jdbc.sql("SELECT id FROM identity.identity WHERE username = :u").param("u", username).query(UUID.class).optional();
    }

    @Override
    public List<Scoped<Identity>> listIdentities(ScopeFilter filter, UUID personId, String state, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(IDENTITY_SELECT).append(" WHERE i.type <> 'SYSTEM' AND ")
                .append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (personId != null) {
            sql.append(" AND i.person_id = :person");
            params.put("person", personId);
        }
        if (state != null) {
            sql.append(" AND i.state = :state");
            params.put("state", state);
        }
        if (page.after() != null) {
            sql.append(" AND i.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY i.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new Scoped<>(identity(rs), rs.getString("org_path"), rs.getString("display_name"),
                        rs.getBoolean("has_platform_user"))).list();
    }

    @Override
    public List<Scoped<Identity>> listIdentities(ScopeFilter filter, IdentityQuery q, PageRequest page) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder sql = new StringBuilder(IDENTITY_SELECT).append(" WHERE i.type <> 'SYSTEM' AND ")
                .append(ScopeSql.predicate(filter, SCOPE, params, "s_"));
        if (q.personId() != null) {
            sql.append(" AND i.person_id = :person");
            params.put("person", q.personId());
        }
        if (q.state() != null) {
            sql.append(" AND i.state = :state");
            params.put("state", q.state());
        }
        if (q.type() != null) {
            sql.append(" AND i.type = :type");
            params.put("type", q.type());
        }
        if (q.orgUnitId() != null) {
            sql.append(" AND p.org_unit_id = :ou");
            params.put("ou", q.orgUnitId());
        }
        if (q.search() != null && !q.search().isBlank()) {
            sql.append(" AND (lower(i.username) LIKE :q ESCAPE '\\' OR lower(p.display_name) LIKE :q ESCAPE '\\' OR lower(coalesce(p.email, '')) LIKE :q ESCAPE '\\')");
            String s = q.search().trim().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            params.put("q", "%" + s + "%");
        }
        if (page.after() != null) {
            sql.append(" AND i.id > :after");
            params.put("after", page.after());
        }
        sql.append(" ORDER BY i.id LIMIT :limit");
        params.put("limit", page.limit() + 1);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new Scoped<>(identity(rs), rs.getString("org_path"), rs.getString("display_name"),
                        rs.getBoolean("has_platform_user"))).list();
    }

    @Override
    public List<Identity> findExpired(Instant now, int limit) {
        return jdbc.sql("""
                SELECT * FROM identity.identity WHERE valid_until <= :now AND state IN ('PENDING', 'ACTIVE', 'SUSPENDED')
                ORDER BY valid_until LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("now", ts(now)).param("limit", limit).query((rs, n) -> identity(rs)).list();
    }

    @Override
    public Optional<IdentitySummary> summary(UUID identityId) {
        return jdbc.sql("""
                SELECT i.id, i.person_id, i.username, i.type, i.state, i.valid_until, p.display_name, p.email, p.org_unit_id, ou.path AS org_path
                FROM identity.identity i JOIN identity.person p ON p.id = i.person_id
                LEFT JOIN organization.org_unit ou ON ou.id = p.org_unit_id WHERE i.id = :id""")
                .param("id", identityId)
                .query((rs, n) -> new IdentitySummary(uuid(rs, "id"), uuid(rs, "person_id"), rs.getString("username"),
                        rs.getString("display_name"), rs.getString("email"), rs.getString("type"), rs.getString("state"),
                        instant(rs, "valid_until"), uuid(rs, "org_unit_id"), rs.getString("org_path")))
                .optional();
    }

    // ------------------------------------------------------------------------------------------------ platform users

    @Override
    public Optional<PlatformUserRef> findBySubject(String subject) {
        return jdbc.sql("""
                SELECT pu.identity_id, i.state FROM identity.platform_user pu JOIN identity.identity i ON i.id = pu.identity_id
                WHERE pu.keycloak_subject = :sub""")
                .param("sub", subject).query((rs, n) -> new PlatformUserRef(uuid(rs, "identity_id"), rs.getString("state"))).optional();
    }

    @Override
    public boolean subjectExists(String subject) {
        return jdbc.sql("SELECT count(*) FROM identity.platform_user WHERE keycloak_subject = :s").param("s", subject)
                .query(Long.class).single() > 0;
    }

    @Override
    public boolean hasPlatformUser(UUID identityId) {
        return jdbc.sql("SELECT count(*) FROM identity.platform_user WHERE identity_id = :id").param("id", identityId)
                .query(Long.class).single() > 0;
    }

    @Override
    public Optional<String> subjectOf(UUID identityId) {
        return jdbc.sql("SELECT keycloak_subject FROM identity.platform_user WHERE identity_id = :id").param("id", identityId)
                .query(String.class).optional();
    }

    @Override
    public void insertPlatformUser(UUID identityId, String subject, Instant now) {
        jdbc.sql("INSERT INTO identity.platform_user (identity_id, keycloak_subject, created_at) VALUES (:id, :sub, :now)")
                .param("id", identityId).param("sub", subject).param("now", ts(now)).update();
    }

    @Override
    public void touchLogin(UUID identityId, Instant now) {
        jdbc.sql("UPDATE identity.platform_user SET last_login_at = :now WHERE identity_id = :id")
                .param("now", ts(now)).param("id", identityId).update();
    }

    @Override
    public long platformUserCount() {
        return jdbc.sql("SELECT count(*) FROM identity.platform_user").query(Long.class).single();
    }

    @Override
    public boolean markBootstrapCompleted(Instant now, String subject) {
        try {
            return jdbc.sql("INSERT INTO identity.platform_setting (key, value, created_at) VALUES ('bootstrap.completed', :sub, :now)")
                    .param("sub", subject).param("now", ts(now)).update() == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------ mapping

    static Person person(ResultSet rs) throws SQLException {
        return new Person(uuid(rs, "id"), uuid(rs, "org_unit_id"), rs.getString("employee_id"), rs.getString("given_name"),
                rs.getString("family_name"), rs.getString("display_name"), uuid(rs, "position_id"), uuid(rs, "location_id"),
                uuid(rs, "manager_person_id"), Person.EmploymentStatus.valueOf(rs.getString("employment_status")),
                date(rs, "start_date"), date(rs, "end_date"), rs.getString("email"), rs.getString("phone"), rs.getLong("version"));
    }

    static Identity identity(ResultSet rs) throws SQLException {
        return new Identity(uuid(rs, "id"), uuid(rs, "person_id"), IdentityType.valueOf(rs.getString("type")), rs.getString("username"),
                IdentityState.valueOf(rs.getString("state")), rs.getString("state_reason"), instant(rs, "valid_from"),
                instant(rs, "valid_until"), rs.getLong("version"));
    }
}
