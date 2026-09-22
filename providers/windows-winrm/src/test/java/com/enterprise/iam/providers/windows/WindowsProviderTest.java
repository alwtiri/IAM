package com.enterprise.iam.providers.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.kernel.Json;
import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.CredentialResolver;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.NativeAccountStatus;
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Windows provider semantics against a simulated host that understands exactly the provider's scripts. */
class WindowsProviderTest {

    static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    static final class FakeHost implements WinRmTransport {
        static final class User {
            final String name;
            final String sid;
            boolean enabled = true;
            boolean locked;
            String password;
            String passwordLastSet = "2026-01-01T00:00:00.0000000Z";
            final List<Map<String, Object>> groups = new ArrayList<>();

            User(String name, int rid) {
                this.name = name;
                this.sid = "S-1-5-21-1111-2222-3333-" + rid;
            }
        }

        final Map<String, User> users = new LinkedHashMap<>();
        final List<String> changes = new ArrayList<>();
        String refuse;
        boolean admin = true;
        boolean ignoreChanges;
        String lastParams;

        FakeHost() {
            add(new User("Administrator", 500)).groups.add(Map.of("name", "Administrators", "sid", "S-1-5-32-544"));
            add(new User("svc-iam", 1001)).groups.add(Map.of("name", "Administrators", "sid", "S-1-5-32-544"));
            add(new User("ops", 1002)).groups.add(Map.of("name", "Backup Operators", "sid", "S-1-5-32-551"));
            add(new User("alice", 1003)).groups.add(Map.of("name", "Users", "sid", "S-1-5-32-545"));
            add(new User("Guest", 501)).enabled = false;
        }

        User add(User u) {
            users.put(u.name, u);
            return u;
        }

        Map<String, Object> json(User u) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", u.name);
            m.put("sid", u.sid);
            m.put("enabled", u.enabled);
            m.put("locked", u.locked);
            m.put("fullName", u.name.equals("alice") ? "Alice Admin" : "");
            m.put("lastLogon", "2026-09-20T08:00:00.0000000Z");
            m.put("passwordLastSet", u.passwordLastSet);
            m.put("passwordExpires", u.name.equals("alice") ? "2027-01-01T00:00:00.0000000Z" : null);
            m.put("accountExpires", null);
            m.put("groups", u.groups);
            return m;
        }

        @Override
        public Result run(Endpoint endpoint, Secret password, String script, String parametersJson) throws IOException {
            if ("connect".equals(refuse)) {
                throw new ConnectionException("cannot connect to win01:5986 (ConnectException)");
            }
            if ("auth".equals(refuse)) {
                throw new AuthenticationException("WinRM rejected the credentials (HTTP 401)");
            }
            assertTrue(endpoint.url().startsWith("https://"));
            lastParams = parametersJson;
            Map<String, Object> p = Json.parseObject(parametersJson);
            if (script.equals(WindowsProvider.VALIDATE)) {
                return new Result(0, Json.write(Map.of("os", "Windows Server 2022", "ps", "5.1", "admin", admin, "localAccounts", true)), "");
            }
            if (script.equals(WindowsProvider.DISCOVER_ACCOUNTS)) {
                return new Result(0, Json.write(users.values().stream().map(this::json).toList()), "");
            }
            if (script.equals(WindowsProvider.READ)) {
                User u = users.values().stream().filter(x -> x.sid.equals(p.get("sid")) || x.name.equalsIgnoreCase(String.valueOf(p.get("name"))))
                        .findFirst().orElse(null);
                return new Result(0, u == null ? "null" : Json.write(json(u)), "");
            }
            User u = users.values().stream().filter(x -> x.sid.equals(p.get("sid"))).findFirst().orElseThrow();
            changes.add(script + " " + u.name);
            if (!ignoreChanges) {
                if (script.equals(WindowsProvider.DISABLE)) {
                    u.enabled = false;
                } else if (script.equals(WindowsProvider.ENABLE)) {
                    u.enabled = true;
                } else if (script.equals(WindowsProvider.UNLOCK)) {
                    u.locked = false;
                } else if (script.equals(WindowsProvider.ROTATE)) {
                    u.password = String.valueOf(p.get("password"));
                    u.passwordLastSet = NOW.toString();
                }
            }
            return new Result(0, "", "");
        }
    }

    static ProviderConnection connection(Map<String, String> extra) {
        Map<String, String> s = new LinkedHashMap<>(Map.of("username", "WIN01\\svc-iam", "pinnedCertificateSha256", "ab:cd"));
        s.putAll(extra);
        return new ProviderConnection(UUID.randomUUID(), WindowsProvider.TYPE, "https://win01.example.org:5986/wsman", s, new CredentialHandle("ch_win"));
    }

    static OperationContext ctx() {
        return new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, NOW.plusSeconds(60), h -> Secret.of("P@ss"));
    }

    final FakeHost host = new FakeHost();
    final WindowsProviderFactory factory = new WindowsProviderFactory(host, Clock.fixed(NOW, ZoneOffset.UTC));
    final Provider provider = factory.create(connection(Map.of()));

    @Test
    void discoveryMapsStatusPrivilegeAndBuiltIns() {
        List<AccountState> items = provider.discoverAccounts(ctx(), null).value().orElseThrow().items();
        assertEquals(5, items.size());
        Map<String, AccountState> by = new LinkedHashMap<>();
        items.forEach(a -> by.put(a.account().name(), a));
        assertTrue(by.get("Administrator").privileged());
        assertEquals("true", by.get("Administrator").attributes().get("builtIn"));
        assertEquals("member of Backup Operators", by.get("ops").attributes().get("privilegeReason"));
        assertFalse(by.get("alice").privileged());
        assertEquals("Alice Admin", by.get("alice").attributes().get("displayName"));
        assertEquals(NativeAccountStatus.DISABLED, by.get("Guest").status());
        assertEquals("S-1-5-21-1111-2222-3333-1003", by.get("alice").account().nativeId());
        assertEquals(Instant.parse("2026-09-20T08:00:00Z"), by.get("alice").lastLogin());
    }

    @Test
    void disableEnableAndUnlockAreVerifiedAndIdempotent() {
        OperationResult<AccountState> d = provider.disableAccount(ctx(), new AccountRef(null, "alice"));
        assertEquals(OperationOutcome.SUCCEEDED, d.outcome(), d.toString());
        assertEquals(NativeAccountStatus.DISABLED, d.value().orElseThrow().status());
        int n = host.changes.size();
        provider.disableAccount(ctx(), new AccountRef("S-1-5-21-1111-2222-3333-1003", "alice"));
        assertEquals(n, host.changes.size(), "no second change");
        assertEquals(OperationOutcome.SUCCEEDED, provider.enableAccount(ctx(), new AccountRef(null, "alice")).outcome());
        host.users.get("alice").locked = true;
        assertEquals(NativeAccountStatus.LOCKED, provider.getAccountState(ctx(), new AccountRef(null, "alice")).value().orElseThrow().status());
        assertEquals(OperationOutcome.SUCCEEDED, provider.unlockAccount(ctx(), new AccountRef(null, "alice")).outcome());
        assertFalse(host.users.get("alice").locked);
    }

    @Test
    void rotationSetsTheVaultedPasswordAndVerifiesPasswordLastSet() {
        OperationContext ctx = new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, NOW.plusSeconds(60),
                h -> Secret.of(h.value().equals("ch_new") ? "Rot@ted-123456" : "P@ss"));
        var r = provider.rotatePassword(ctx, new com.enterprise.iam.provider.spi.model.PasswordChange(new AccountRef(null, "Administrator"), null,
                new CredentialHandle("ch_new")));
        assertEquals(OperationOutcome.SUCCEEDED, r.outcome(), r.toString());
        assertEquals("Rot@ted-123456", host.users.get("Administrator").password);
        assertEquals("PROTECTED_ACCOUNT", provider.rotatePassword(ctx, new com.enterprise.iam.provider.spi.model.PasswordChange(
                new AccountRef(null, "svc-iam"), null, new CredentialHandle("ch_new"))).error().orElseThrow().code());
        host.ignoreChanges = true;
        assertEquals(OperationOutcome.UNKNOWN, provider.rotatePassword(ctx, new com.enterprise.iam.provider.spi.model.PasswordChange(
                new AccountRef(null, "alice"), null, new CredentialHandle("ch_new"))).outcome());
    }

    @Test
    void changeThatDoesNotReadBackIsUnknown() {
        host.ignoreChanges = true;
        assertEquals(OperationOutcome.UNKNOWN, provider.disableAccount(ctx(), new AccountRef(null, "alice")).outcome());
    }

    @Test
    void builtInAdministratorAndOwnServiceAccountAreProtected() {
        assertEquals("PROTECTED_ACCOUNT", provider.disableAccount(ctx(), new AccountRef(null, "Administrator")).error().orElseThrow().code());
        assertEquals("PROTECTED_ACCOUNT", provider.disableAccount(ctx(), new AccountRef(null, "svc-iam")).error().orElseThrow().code());
        assertTrue(host.changes.isEmpty());
    }

    @Test
    void namesTravelAsParametersAndInvalidNamesNeverReachTheHost() {
        for (String bad : List.of("a;b|c", "x\"y", "a*", "<script>", "averyveryverylongname12")) {
            assertEquals("INVALID_ACCOUNT_NAME", provider.disableAccount(ctx(), new AccountRef(null, bad)).error().orElseThrow().code(), bad);
        }
        assertTrue(host.changes.isEmpty());
        provider.getAccountState(ctx(), new AccountRef(null, "alice"));
        assertEquals(Map.of("name", "alice"), Json.parseObject(host.lastParams));
        String wrapped = HttpWinRmTransport.wrap(WindowsProvider.READ, "{\"name\":\"x'); Remove-Item C:\\\\ -Recurse; ('\"}");
        assertFalse(wrapped.contains("Remove-Item"), "parameters are base64-encoded, never spliced into the script");
        String b64 = wrapped.substring(wrapped.indexOf("FromBase64String('") + 18, wrapped.indexOf("'))"));
        assertTrue(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8).contains("Remove-Item"));
    }

    @Test
    void validationAndErrorsAreClassified() {
        assertTrue(provider.validateConnection(ctx()).isSuccess());
        host.admin = false;
        assertEquals("PRIVILEGE_MISSING", provider.validateConnection(ctx()).error().orElseThrow().code());
        host.refuse = "connect";
        assertTrue(provider.getAccountState(ctx(), new AccountRef(null, "alice")).error().orElseThrow().retryable());
        host.refuse = "auth";
        assertEquals("AUTHENTICATION_FAILED", provider.getAccountState(ctx(), new AccountRef(null, "alice")).error().orElseThrow().code());
        host.refuse = null;
        OperationContext noVault = new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, NOW.plusSeconds(60), h -> {
            throw new CredentialResolver.SecretsUnavailableException("vault down");
        });
        assertEquals("SECRETS_UNAVAILABLE", provider.getAccountState(noVault, new AccountRef(null, "alice")).error().orElseThrow().code());
        assertEquals("ACCOUNT_NOT_FOUND", provider.getAccountState(ctx(), new AccountRef(null, "ghost")).error().orElseThrow().code());
    }

    @Test
    void configurationIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ProviderConnection(UUID.randomUUID(), WindowsProvider.TYPE,
                "http://win01:5985/wsman", Map.of("username", "svc"), new CredentialHandle("ch_x"))), "HTTP refused");
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ProviderConnection(UUID.randomUUID(), WindowsProvider.TYPE,
                "https://win01", Map.of(), new CredentialHandle("ch_x"))));
        // https://host without a path defaults to port 5986 and /wsman
        assertTrue(factory.create(new ProviderConnection(UUID.randomUUID(), WindowsProvider.TYPE, "https://win01",
                Map.of("username", "svc-iam"), new CredentialHandle("ch_x"))).validateConnection(ctx()).isSuccess());
    }

    @Test
    void wsmanXmlParsingRejectsDoctypes() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
        assertEquals(null, HttpWinRmTransport.parse(evil.getBytes(StandardCharsets.UTF_8)));
        assertEquals("a&amp;b&lt;c", HttpWinRmTransport.xml("a&b<c"));
    }
}
