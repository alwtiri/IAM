package com.enterprise.iam.providers.postgresql;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.Capability;
import com.enterprise.iam.provider.spi.CapabilityDescriptor;
import com.enterprise.iam.provider.spi.CredentialResolver;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderOperation;
import com.enterprise.iam.provider.spi.ProviderTypeId;
import com.enterprise.iam.provider.spi.VerificationMode;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.ConnectionReport;
import com.enterprise.iam.provider.spi.model.DiscoverySummary;
import com.enterprise.iam.provider.spi.model.GroupRef;
import com.enterprise.iam.provider.spi.model.NativeAccountStatus;
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.model.PasswordChange;
import com.enterprise.iam.provider.spi.result.OperationResult;
import com.enterprise.iam.provider.spi.result.ProviderError;
import com.enterprise.iam.provider.spi.result.Verification;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PostgreSQL provider for database roles (login users), agentless. Disables with {@code ALTER ROLE ... NOLOGIN}, enables
 * with {@code LOGIN}, and verifies by reading {@code pg_roles} back (G5). Statements are built by the server with
 * {@code format('%I')} from bound parameters, so role names can never inject SQL.
 *
 * <p>Endpoint {@code postgresql://host:5432/database}. Settings: {@code username} (needs CREATEROLE; superuser to manage
 * superusers), {@code sslMode} ({@code verify-full} default, {@code verify-ca}, {@code require}; {@code disable} only with
 * {@code allowInsecure=true}), {@code caCertificatePem}, {@code allowSuperuserChanges} (default false),
 * {@code privilegedRoles} (default: pg_write_all_data, pg_execute_server_program, pg_read_server_files,
 * pg_write_server_files, pg_signal_backend, pg_checkpoint).
 *
 * <p>Accounts are roles that can log in, plus roles that cannot log in and have no members (disabled users); group
 * roles (with members) are memberships, not accounts.
 */
public final class PostgresProvider implements Provider {

    public static final ProviderTypeId TYPE = ProviderTypeId.of("postgresql");
    static final String VERSION = "1.0.0";
    static final List<String> DEFAULT_PRIVILEGED = List.of("pg_write_all_data", "pg_execute_server_program", "pg_read_server_files",
            "pg_write_server_files", "pg_signal_backend", "pg_checkpoint");

    static final String ROLES_SQL = """
            SELECT r.oid::text AS oid, r.rolname AS name, r.rolcanlogin AS can_login, r.rolsuper AS superuser, r.rolcreaterole AS create_role,
                   r.rolcreatedb AS create_db, r.rolreplication AS replication, r.rolbypassrls AS bypass_rls, r.rolvaliduntil AS valid_until,
                   r.rolconnlimit AS conn_limit,
                   COALESCE((SELECT array_agg(g.rolname ORDER BY g.rolname) FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
                             WHERE m.member = r.oid), '{}') AS member_of,
                   EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.roleid = r.oid) AS has_members
            FROM pg_roles r WHERE r.rolname !~ '^pg_'""";
    static final String ACCOUNTS_SQL = ROLES_SQL + " AND (r.rolcanlogin OR NOT EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.roleid = r.oid)) ORDER BY r.rolname";
    static final String ONE_BY_NAME = ROLES_SQL + " AND r.rolname = ?";
    static final String ONE_BY_OID = ROLES_SQL + " AND r.oid = ?::oid";
    static final String VALIDATE_SQL = """
            SELECT version() AS version, current_user AS me, (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS superuser,
                   (SELECT rolcreaterole FROM pg_roles WHERE rolname = current_user) AS create_role""";
    static final String COUNT_SQL = "SELECT count(*) FILTER (WHERE rolcanlogin) AS logins, count(*) AS roles FROM pg_roles WHERE rolname !~ '^pg_'";
    static final String FORMAT_SQL = "SELECT format(?, ?) AS stmt";
    static final String FORMAT2_SQL = "SELECT format(?, ?, ?) AS stmt";
    static final int SCRAM_ITERATIONS = 4096;

    static final ProviderDescriptor DESCRIPTOR = ProviderDescriptor.builder(TYPE, VERSION)
            .capability(CapabilityDescriptor.supported(Capability.CONNECTION_VALIDATION, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISCOVERY, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_STATE_READ, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_ENABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_UNLOCK, "PostgreSQL has no account lockout"))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_CREATE, "provisioning follows with request fulfilment"))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_DELETE, "deletion is not implemented in provider version " + VERSION))
            .capability(CapabilityDescriptor.unsupported(Capability.PASSWORD_RESET, "password operations follow with credential management"))
            .capability(CapabilityDescriptor.supported(Capability.PASSWORD_ROTATION, VerificationMode.LOGIN_TEST))
            .capability(CapabilityDescriptor.unsupported(Capability.GROUP_DISCOVERY, "group roles are reported as memberships per account"))
            .build();

    private final ProviderConnection connection;
    private final DbSessions sessions;
    private final Clock clock;
    private final DbSessions.Target target;
    private final boolean allowSuperuserChanges;
    private final Set<String> privilegedRoles;

    PostgresProvider(ProviderConnection connection, DbSessions sessions, Clock clock) {
        this.connection = connection;
        this.sessions = sessions;
        this.clock = clock;
        URI uri = URI.create(connection.endpoint());
        if (!("postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint must look like postgresql://host:5432/database");
        }
        Map<String, String> s = connection.settings();
        String username = s.get("username");
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("setting 'username' is required");
        }
        String sslMode = s.getOrDefault("sslMode", "verify-full").trim();
        if (!Set.of("verify-full", "verify-ca", "require", "disable").contains(sslMode)) {
            throw new IllegalArgumentException("sslMode must be verify-full, verify-ca, require or disable");
        }
        if ("disable".equals(sslMode) && !"true".equalsIgnoreCase(s.get("allowInsecure"))) {
            throw new IllegalArgumentException("sslMode=disable would send the password in clear text; allowInsecure=true only for labs");
        }
        String db = uri.getPath() == null || uri.getPath().length() <= 1 ? "postgres" : uri.getPath().substring(1);
        String ca = s.get("caCertificatePem");
        this.target = new DbSessions.Target(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 5432, db, username.trim(), sslMode,
                ca == null || ca.isBlank() ? null : ca.trim(), Duration.ofSeconds(20));
        this.allowSuperuserChanges = "true".equalsIgnoreCase(s.get("allowSuperuserChanges"));
        String pr = s.get("privilegedRoles");
        this.privilegedRoles = (pr == null || pr.isBlank() ? DEFAULT_PRIVILEGED.stream() : Arrays.stream(pr.split(",")))
                .map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        return call(ctx, ProviderOperation.VALIDATE_CONNECTION, db -> {
            Map<String, Object> r = db.query(VALIDATE_SQL).get(0);
            if (!Boolean.TRUE.equals(r.get("superuser")) && !Boolean.TRUE.equals(r.get("create_role"))) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, new ProviderError("PRIVILEGE_MISSING",
                        "the service role needs CREATEROLE to enable and disable logins", false, false));
            }
            String version = String.valueOf(r.get("version"));
            return OperationResult.read(ProviderOperation.VALIDATE_CONNECTION,
                    new ConnectionReport("PostgreSQL", version.length() > 80 ? version.substring(0, 80) : version, null));
        });
    }

    @Override
    public OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        return call(ctx, ProviderOperation.DISCOVER, db -> {
            Map<String, Object> r = db.query(COUNT_SQL).get(0);
            return OperationResult.read(ProviderOperation.DISCOVER, new DiscoverySummary(num(r.get("logins")), num(r.get("roles")), 0));
        });
    }

    @Override
    public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        return call(ctx, ProviderOperation.DISCOVER_ACCOUNTS, db -> {
            List<AccountState> items = new ArrayList<>();
            for (Map<String, Object> row : db.query(ACCOUNTS_SQL)) {
                items.add(state(row));
            }
            return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(items, null));
        });
    }

    @Override
    public OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        return call(ctx, ProviderOperation.GET_ACCOUNT_STATE, db -> {
            Map<String, Object> row = read(db, account);
            return row == null ? OperationResult.failed(ProviderOperation.GET_ACCOUNT_STATE, notFound(account))
                    : OperationResult.read(ProviderOperation.GET_ACCOUNT_STATE, state(row));
        });
    }

    @Override
    public OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        return call(ctx, ProviderOperation.VERIFY_OPERATION, db -> {
            Map<String, Object> row = read(db, account);
            return row == null ? OperationResult.unknown(ProviderOperation.VERIFY_OPERATION, "role not found")
                    : OperationResult.read(ProviderOperation.VERIFY_OPERATION, state(row));
        });
    }

    @Override
    public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.DISABLE_ACCOUNT, "ALTER ROLE %I NOLOGIN", false);
    }

    @Override
    public OperationResult<AccountState> enableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.ENABLE_ACCOUNT, "ALTER ROLE %I LOGIN", true);
    }

    private OperationResult<AccountState> change(OperationContext ctx, AccountRef account, ProviderOperation op, String template, boolean login) {
        if (!validName(account)) {
            return OperationResult.failed(op, new ProviderError("INVALID_ACCOUNT_NAME", "not a valid role name or oid", false, false));
        }
        return call(ctx, op, db -> {
            Map<String, Object> before = read(db, account);
            if (before == null) {
                return OperationResult.failed(op, notFound(account));
            }
            String name = String.valueOf(before.get("name"));
            if (name.equals(target.username()) || (Boolean.TRUE.equals(before.get("superuser")) && !allowSuperuserChanges)) {
                return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT",
                        "the service role and superusers are not changed (allowSuperuserChanges=true to manage superusers)", false, false));
            }
            if (Boolean.TRUE.equals(before.get("can_login")) != login) {
                String stmt = String.valueOf(db.query(FORMAT_SQL, template, name).get(0).get("stmt"));
                try {
                    db.execute(stmt);
                } catch (DbSession.StatementException e) {
                    String code = "42501".equals(e.sqlState()) ? "PRIVILEGE_MISSING" : "PROVIDER_REJECTED";
                    return OperationResult.failed(op, new ProviderError(code, "PostgreSQL refused the change (SQLState " + e.sqlState() + ")", false, false));
                }
            }
            Map<String, Object> after = read(db, new AccountRef(String.valueOf(before.get("oid")), name));
            if (after == null) {
                return OperationResult.unknown(op, "change sent but the role could not be read back");
            }
            if (Boolean.TRUE.equals(after.get("can_login")) != login) {
                return OperationResult.unknown(op, "read-back shows LOGIN=" + after.get("can_login"));
            }
            return OperationResult.succeeded(op, state(after), new Verification(VerificationMode.READ_BACK, clock.instant(),
                    "role " + name + " reads back " + (login ? "LOGIN" : "NOLOGIN")));
        });
    }

    /**
     * Rotates a role's password. The platform computes a SCRAM-SHA-256 verifier locally, so neither the password nor
     * anything reversible reaches the server or its statement log; the change is verified by logging in as the role with
     * the new password (LOGIN_TEST). Roles that cannot log in cannot be verified and end UNKNOWN.
     */
    @Override
    public OperationResult<Void> rotatePassword(OperationContext ctx, PasswordChange change) {
        ProviderOperation op = ProviderOperation.ROTATE_PASSWORD;
        if (!validName(change.account())) {
            return OperationResult.failed(op, new ProviderError("INVALID_ACCOUNT_NAME", "not a valid role name or oid", false, false));
        }
        return call(ctx, op, db -> {
            Map<String, Object> before = read(db, change.account());
            if (before == null) {
                return OperationResult.failed(op, notFound(change.account()));
            }
            String name = String.valueOf(before.get("name"));
            if (name.equals(target.username()) || (Boolean.TRUE.equals(before.get("superuser")) && !allowSuperuserChanges)) {
                return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT",
                        "the service role and superusers are not rotated here (allowSuperuserChanges=true to manage superusers)", false, false));
            }
            Secret pw;
            try {
                pw = ctx.credentials().redeem(change.newSecret());
            } catch (CredentialResolver.SecretsUnavailableException e) {
                return OperationResult.secretsUnavailable(op);
            }
            try (pw) {
                String verifier;
                char[] value = pw.reveal(); // provider credential use: SCRAM verifier derivation and login test only, cleared below
                try {
                    verifier = Scram.verifier(value, SCRAM_ITERATIONS);
                } finally {
                    Arrays.fill(value, '\0');
                }
                String stmt = String.valueOf(db.query(FORMAT2_SQL, "ALTER ROLE %I PASSWORD %L", name, verifier).get(0).get("stmt"));
                try {
                    db.execute(stmt);
                } catch (DbSession.StatementException e) {
                    String code = "42501".equals(e.sqlState()) ? "PRIVILEGE_MISSING" : "PROVIDER_REJECTED";
                    return OperationResult.failed(op, new ProviderError(code, "PostgreSQL refused the password change (SQLState " + e.sqlState() + ")", false, false));
                }
                if (!Boolean.TRUE.equals(before.get("can_login"))) {
                    return OperationResult.unknown(op, "password set, but role " + name + " cannot log in, so it cannot be verified");
                }
                DbSessions.Target asRole = new DbSessions.Target(target.host(), target.port(), target.database(), name, target.sslMode(),
                        target.caCertificatePem(), target.timeout());
                try (DbSession probe = sessions.open(asRole, pw)) {
                    probe.query(VALIDATE_SQL);
                } catch (DbSession.AuthenticationException e) {
                    return OperationResult.unknown(op, "login test with the new password was rejected (pg_hba may not allow this role from the worker)");
                } catch (IOException e) {
                    return OperationResult.unknown(op, "login test could not be completed: " + e.getMessage());
                }
            }
            return OperationResult.succeeded(op, null, new Verification(VerificationMode.LOGIN_TEST, clock.instant(),
                    "role " + name + " logged in with the new password"));
        });
    }

    private Map<String, Object> read(DbSession db, AccountRef ref) throws IOException {
        List<Map<String, Object>> rows = ref.nativeId() != null && ref.nativeId().matches("^[0-9]{1,10}$")
                ? db.query(ONE_BY_OID, ref.nativeId()) : db.query(ONE_BY_NAME, ref.name());
        return rows.isEmpty() ? null : rows.get(0);
    }

    AccountState state(Map<String, Object> r) {
        boolean login = Boolean.TRUE.equals(r.get("can_login"));
        Instant validUntil = r.get("valid_until") instanceof Instant i ? i : null;
        NativeAccountStatus status = !login ? NativeAccountStatus.DISABLED
                : validUntil != null && validUntil.isBefore(clock.instant()) ? NativeAccountStatus.EXPIRED : NativeAccountStatus.ENABLED;
        List<GroupRef> groups = new ArrayList<>();
        String privilegedVia = Boolean.TRUE.equals(r.get("superuser")) ? "superuser"
                : Boolean.TRUE.equals(r.get("create_role")) ? "can create roles"
                : Boolean.TRUE.equals(r.get("replication")) ? "replication" : Boolean.TRUE.equals(r.get("bypass_rls")) ? "bypasses row security" : null;
        if (r.get("member_of") instanceof List<?> ms) {
            for (Object m : ms) {
                String g = String.valueOf(m);
                groups.add(new GroupRef(g, g));
                if (privilegedVia == null && privilegedRoles.contains(g)) {
                    privilegedVia = "member of " + g;
                }
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("oid", String.valueOf(r.get("oid")));
        attributes.put("canLogin", String.valueOf(login));
        attributes.put("createDb", String.valueOf(Boolean.TRUE.equals(r.get("create_db"))));
        if (r.get("conn_limit") instanceof Number n && n.intValue() >= 0) {
            attributes.put("connectionLimit", String.valueOf(n.intValue()));
        }
        if (validUntil != null) {
            attributes.put("validUntil", validUntil.toString());
        }
        if (privilegedVia != null) {
            attributes.put("privilegeReason", privilegedVia);
        }
        return new AccountState(new AccountRef(String.valueOf(r.get("oid")), String.valueOf(r.get("name"))), status, privilegedVia != null,
                groups, null, null, validUntil, attributes);
    }

    @FunctionalInterface
    private interface Work<T> {
        OperationResult<T> run(DbSession db) throws IOException;
    }

    private <T> OperationResult<T> call(OperationContext ctx, ProviderOperation op, Work<T> work) {
        if (connection.credential() == null) {
            return OperationResult.secretsUnavailable(op);
        }
        Secret password;
        try {
            password = ctx.credentials().redeem(connection.credential());
        } catch (CredentialResolver.SecretsUnavailableException e) {
            return OperationResult.secretsUnavailable(op);
        }
        try (password; DbSession db = sessions.open(target, password)) {
            return work.run(db);
        } catch (DbSession.AuthenticationException e) {
            return OperationResult.failed(op, new ProviderError("AUTHENTICATION_FAILED", e.getMessage(), false, false));
        } catch (DbSession.ConnectionException e) {
            return OperationResult.failed(op, new ProviderError("CONNECTION_FAILED", e.getMessage(), true, false));
        } catch (IOException e) {
            return OperationResult.failed(op, new ProviderError("PROVIDER_ERROR", e.getMessage(), true, op.mutating()));
        }
    }

    private static boolean validName(AccountRef ref) {
        return (ref.nativeId() != null && ref.nativeId().matches("^[0-9]{1,10}$"))
                || (ref.name() != null && !ref.name().isEmpty() && ref.name().length() <= 63 && ref.name().indexOf('\0') < 0);
    }

    private static ProviderError notFound(AccountRef ref) {
        return new ProviderError("ACCOUNT_NOT_FOUND", "no role " + ref.name(), false, false);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
