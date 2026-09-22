package com.enterprise.iam.providers.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.NativeAccountStatus;
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostgresProviderTest {

    static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    /** In-memory pg_roles that answers the provider's SQL (matched by constant). */
    static final class FakeDb implements DbSessions {
        final Map<String, Map<String, Object>> roles = new LinkedHashMap<>();
        final List<String> statements = new ArrayList<>();
        boolean serviceCanCreateRoles = true;
        String refuse;
        String denyState;

        FakeDb() {
            role("postgres", 10, true, true, List.of());
            role("iam_service", 16384, true, false, List.of());
            role("app_rw", 16385, true, false, List.of("pg_write_all_data"));
            role("alice", 16386, true, false, List.of("readers"));
            role("bob_old", 16387, false, false, List.of());
            Map<String, Object> readers = role("readers", 16388, false, false, List.of());
            readers.put("has_members", true);
        }

        Map<String, Object> role(String name, int oid, boolean login, boolean superuser, List<String> memberOf) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("oid", String.valueOf(oid));
            r.put("name", name);
            r.put("can_login", login);
            r.put("superuser", superuser);
            r.put("create_role", name.equals("iam_service"));
            r.put("create_db", false);
            r.put("replication", false);
            r.put("bypass_rls", false);
            r.put("valid_until", null);
            r.put("conn_limit", -1);
            r.put("member_of", memberOf);
            r.put("has_members", false);
            roles.put(name, r);
            return r;
        }

        @Override
        public DbSession open(Target target, Secret password) throws IOException {
            if ("connect".equals(refuse)) {
                throw new DbSession.ConnectionException("cannot connect to db01:5432 (SQLState 08001)");
            }
            if ("auth".equals(refuse)) {
                throw new DbSession.AuthenticationException("login rejected for " + target.username());
            }
            assertEquals("verify-full", target.sslMode());
            return new DbSession() {
                @Override
                public List<Map<String, Object>> query(String sql, Object... params) {
                    if (sql.equals(PostgresProvider.VALIDATE_SQL)) {
                        return List.of(Map.of("version", "PostgreSQL 16.4", "me", "iam_service", "superuser", false, "create_role", serviceCanCreateRoles));
                    }
                    if (sql.equals(PostgresProvider.ACCOUNTS_SQL)) {
                        return roles.values().stream().filter(r -> Boolean.TRUE.equals(r.get("can_login")) || !Boolean.TRUE.equals(r.get("has_members")))
                                .map(LinkedHashMap::new).map(m -> (Map<String, Object>) m).toList();
                    }
                    if (sql.equals(PostgresProvider.ONE_BY_NAME)) {
                        Map<String, Object> r = roles.get(String.valueOf(params[0]));
                        return r == null ? List.of() : List.of(new LinkedHashMap<>(r));
                    }
                    if (sql.equals(PostgresProvider.ONE_BY_OID)) {
                        return roles.values().stream().filter(r -> r.get("oid").equals(params[0])).map(LinkedHashMap::new)
                                .map(m -> (Map<String, Object>) m).toList();
                    }
                    if (sql.equals(PostgresProvider.FORMAT_SQL)) {
                        // server-side format('%I'): quote when needed
                        String name = String.valueOf(params[1]);
                        String ident = name.matches("[a-z_][a-z0-9_]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
                        return List.of(Map.of("stmt", String.valueOf(params[0]).replace("%I", ident)));
                    }
                    throw new AssertionError("unexpected SQL " + sql);
                }

                @Override
                public void execute(String statement) throws IOException {
                    statements.add(statement);
                    if (denyState != null) {
                        throw new DbSession.StatementException(denyState, "denied");
                    }
                    String ident = statement.substring("ALTER ROLE ".length(), statement.lastIndexOf(' '));
                    String name = ident.startsWith("\"") ? ident.substring(1, ident.length() - 1).replace("\"\"", "\"") : ident;
                    roles.get(name).put("can_login", statement.endsWith(" LOGIN"));
                }

                @Override
                public void close() {
                }
            };
        }
    }

    static ProviderConnection connection(Map<String, String> extra) {
        Map<String, String> s = new LinkedHashMap<>(Map.of("username", "iam_service"));
        s.putAll(extra);
        return new ProviderConnection(UUID.randomUUID(), PostgresProvider.TYPE, "postgresql://db01.example.org:5432/app", s, new CredentialHandle("ch_pg"));
    }

    static OperationContext ctx() {
        return new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, NOW.plusSeconds(60), h -> Secret.of("pw"));
    }

    final FakeDb db = new FakeDb();
    final PostgresProviderFactory factory = new PostgresProviderFactory(db, Clock.fixed(NOW, ZoneOffset.UTC));
    final Provider provider = factory.create(connection(Map.of()));

    @Test
    void discoveryListsLoginRolesAndDisabledUsersButNotGroupRoles() {
        List<AccountState> items = provider.discoverAccounts(ctx(), null).value().orElseThrow().items();
        Map<String, AccountState> by = new LinkedHashMap<>();
        items.forEach(a -> by.put(a.account().name(), a));
        assertEquals(List.of("postgres", "iam_service", "app_rw", "alice", "bob_old"), List.copyOf(by.keySet()));
        assertEquals("superuser", by.get("postgres").attributes().get("privilegeReason"));
        assertEquals("member of pg_write_all_data", by.get("app_rw").attributes().get("privilegeReason"));
        assertFalse(by.get("alice").privileged());
        assertEquals(NativeAccountStatus.DISABLED, by.get("bob_old").status());
        assertEquals("16386", by.get("alice").account().nativeId());
    }

    @Test
    void disableAndEnableAreVerifiedIdempotentAndQuoted() {
        var r = provider.disableAccount(ctx(), new AccountRef(null, "alice"));
        assertEquals(OperationOutcome.SUCCEEDED, r.outcome(), r.toString());
        assertEquals(List.of("ALTER ROLE alice NOLOGIN"), db.statements);
        provider.disableAccount(ctx(), new AccountRef("16386", "alice"));
        assertEquals(1, db.statements.size(), "no second change");
        assertEquals(OperationOutcome.SUCCEEDED, provider.enableAccount(ctx(), new AccountRef(null, "alice")).outcome());
        db.role("Weird; DROP TABLE x", 17000, true, false, List.of());
        provider.disableAccount(ctx(), new AccountRef(null, "Weird; DROP TABLE x"));
        assertEquals("ALTER ROLE \"Weird; DROP TABLE x\" NOLOGIN", db.statements.get(2));
    }

    @Test
    void superusersAndTheServiceRoleAreProtected() {
        assertEquals("PROTECTED_ACCOUNT", provider.disableAccount(ctx(), new AccountRef(null, "postgres")).error().orElseThrow().code());
        assertEquals("PROTECTED_ACCOUNT", provider.disableAccount(ctx(), new AccountRef(null, "iam_service")).error().orElseThrow().code());
        assertTrue(db.statements.isEmpty());
    }

    @Test
    void privilegeAndErrorsAreClassified() {
        db.denyState = "42501";
        assertEquals("PRIVILEGE_MISSING", provider.disableAccount(ctx(), new AccountRef(null, "alice")).error().orElseThrow().code());
        db.denyState = null;
        db.serviceCanCreateRoles = false;
        assertEquals("PRIVILEGE_MISSING", provider.validateConnection(ctx()).error().orElseThrow().code());
        db.refuse = "auth";
        assertEquals("AUTHENTICATION_FAILED", provider.getAccountState(ctx(), new AccountRef(null, "alice")).error().orElseThrow().code());
        db.refuse = "connect";
        assertTrue(provider.getAccountState(ctx(), new AccountRef(null, "alice")).error().orElseThrow().retryable());
        db.refuse = null;
        assertEquals("ACCOUNT_NOT_FOUND", provider.getAccountState(ctx(), new AccountRef(null, "ghost")).error().orElseThrow().code());
        assertEquals(OperationOutcome.UNSUPPORTED, provider.unlockAccount(ctx(), new AccountRef(null, "alice")).outcome());
    }

    @Test
    void configurationIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(connection(Map.of("sslMode", "disable"))));
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ProviderConnection(UUID.randomUUID(), PostgresProvider.TYPE,
                "mysql://db01", Map.of("username", "x"), new CredentialHandle("ch"))));
        assertThrows(IllegalArgumentException.class, () -> factory.create(connection(Map.of("sslMode", "prefer"))));
    }
}
