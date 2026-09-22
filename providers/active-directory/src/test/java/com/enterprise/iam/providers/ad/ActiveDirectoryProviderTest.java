package com.enterprise.iam.providers.ad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.CredentialResolver;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.NativeAccountStatus;
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Active Directory provider semantics against an in-memory directory (mapping, paging, verification, errors, safety). */
class ActiveDirectoryProviderTest {

    static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    static final String BASE = "DC=corp,DC=example,DC=org";

    static String fileTime(Instant i) {
        return String.valueOf(i.toEpochMilli() * 10_000L + 116_444_736_000_000_000L);
    }

    /** A tiny directory that understands exactly the requests the provider sends. */
    static final class FakeDirectory implements LdapDirectoryFactory {
        static final class User {
            final String dn;
            final String guid;
            final String sam;
            long uac = 512;
            String lockoutTime = "0";
            String accountExpires = "9223372036854775807";
            String adminCount;
            List<String> memberOf = new ArrayList<>();

            User(String sam, String ou) {
                this.sam = sam;
                this.dn = "CN=" + sam + "," + ou + "," + BASE;
                this.guid = UUID.nameUUIDFromBytes(sam.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            }

            boolean lockedOut() {
                return !"0".equals(lockoutTime);
            }
        }

        final Map<String, User> users = new LinkedHashMap<>();
        final List<String> modifications = new ArrayList<>();
        final List<String> filters = new ArrayList<>();
        int opens;
        String refuse; // "connect" | "auth"
        String rejectModify; // result code
        boolean timeoutModify;
        boolean ignoreModify;
        boolean activeDirectory = true;

        FakeDirectory() {
            add(new User("Administrator", "CN=Users")).adminCount = "1";
            User alice = add(new User("alice", "OU=Admins"));
            alice.memberOf.add("CN=Domain Admins,CN=Users," + BASE);
            User bob = add(new User("bob", "OU=Staff"));
            bob.memberOf.add("CN=Finance\\, Team,OU=Groups," + BASE);
            add(new User("svc-sql", "OU=Service")).uac = 512 | 0x10000;
            add(new User("krbtgt", "CN=Users")).uac = 514;
        }

        User add(User u) {
            users.put(u.sam, u);
            return u;
        }

        @Override
        public LdapDirectory open(Config config, Secret password) throws IOException {
            opens++;
            if ("connect".equals(refuse)) {
                throw new LdapDirectory.ConnectionException("cannot connect to " + config.host() + ":" + config.port() + " (connect error)");
            }
            if ("auth".equals(refuse)) {
                throw new LdapDirectory.AuthenticationException("bind rejected for " + config.bindDn());
            }
            assertEquals(LdapDirectoryFactory.Security.LDAPS, config.security());
            assertEquals(636, config.port());
            return new LdapDirectory() {
                @Override
                public Entry rootDse(List<String> attributes) {
                    return new Entry("", Map.of("dnsHostName", List.of("dc1.corp.example.org"), "domainControllerFunctionality", List.of("7"),
                            "supportedCapabilities", activeDirectory ? List.of(ActiveDirectoryProvider.AD_CAPABILITY_OID) : List.of("1.2.3"),
                            "defaultNamingContext", List.of(BASE)));
                }

                @Override
                public SearchPage search(String baseDn, Scope scope, String filter, List<String> attributes, int pageSize, byte[] cookie) {
                    filters.add(filter);
                    if (scope == Scope.BASE) {
                        if (baseDn.equals(BASE)) {
                            return new SearchPage(List.of(new Entry(BASE, Map.of("lockoutDuration", List.of("-18000000000")))), null);
                        }
                        User u = users.values().stream().filter(x -> baseDn.equals("<GUID=" + x.guid + ">") || baseDn.equals(x.dn))
                                .findFirst().orElse(null);
                        return new SearchPage(u == null ? List.of() : List.of(entry(u, true)), null);
                    }
                    if (filter.equals(ActiveDirectoryProvider.GROUP_FILTER)) {
                        return new SearchPage(List.of(new Entry("CN=g1," + BASE, Map.of()), new Entry("CN=g2," + BASE, Map.of())), null);
                    }
                    if (filter.contains("(sAMAccountName=")) {
                        String name = filter.substring(filter.indexOf("(sAMAccountName=") + 16, filter.length() - 2);
                        User u = users.get(name);
                        return new SearchPage(u == null ? List.of() : List.of(new Entry(u.dn, Map.of())), null);
                    }
                    int start = cookie == null ? 0 : ByteBuffer.wrap(cookie).getInt();
                    List<User> all = new ArrayList<>(users.values());
                    int end = Math.min(all.size(), start + pageSize);
                    List<Entry> page = new ArrayList<>();
                    for (User u : all.subList(start, end)) {
                        page.add(entry(u, false));
                    }
                    byte[] next = end < all.size() ? ByteBuffer.allocate(4).putInt(end).array() : null;
                    return new SearchPage(page, next);
                }

                @Override
                public void replace(String dn, String attribute, String value) throws IOException {
                    modifications.add(dn + " " + attribute + "=" + value);
                    if (timeoutModify) {
                        throw new LdapDirectory.TimeoutException("no response");
                    }
                    if (rejectModify != null) {
                        throw new LdapDirectory.RejectedException(rejectModify, rejectModify);
                    }
                    if (ignoreModify) {
                        return;
                    }
                    User u = users.values().stream().filter(x -> x.dn.equals(dn)).findFirst().orElseThrow();
                    switch (attribute) {
                        case "userAccountControl" -> u.uac = Long.parseLong(value);
                        case "lockoutTime" -> u.lockoutTime = value;
                        default -> throw new IllegalArgumentException(attribute);
                    }
                }

                @Override
                public void close() {
                }
            };
        }

        static LdapDirectory.Entry entry(User u, boolean constructed) {
            Map<String, List<String>> v = new LinkedHashMap<>();
            v.put("sAMAccountName", List.of(u.sam));
            v.put("objectGUID", List.of(u.guid));
            v.put("userAccountControl", List.of(String.valueOf(u.uac)));
            v.put("lockoutTime", List.of(u.lockoutTime));
            v.put("accountExpires", List.of(u.accountExpires));
            v.put("lastLogonTimestamp", List.of(fileTime(NOW.minusSeconds(86_400))));
            v.put("memberOf", u.memberOf);
            if (u.adminCount != null) {
                v.put("adminCount", List.of(u.adminCount));
            }
            if (constructed) {
                v.put("msDS-User-Account-Control-Computed", List.of(u.lockedOut() ? "16" : "0"));
            }
            return new LdapDirectory.Entry(u.dn, v);
        }
    }

    static ProviderConnection connection(Map<String, String> extra) {
        Map<String, String> s = new LinkedHashMap<>(Map.of("bindDn", "svc-iam@corp.example.org", "baseDn", BASE, "pageSize", "10"));
        s.putAll(extra);
        return new ProviderConnection(UUID.randomUUID(), ActiveDirectoryProvider.TYPE, "ldaps://dc1.corp.example.org", s, new CredentialHandle("ch_ad"));
    }

    static OperationContext ctx() {
        return new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, NOW.plusSeconds(60), h -> Secret.of("s3cret"));
    }

    final FakeDirectory dir = new FakeDirectory();
    final ActiveDirectoryProviderFactory factory = new ActiveDirectoryProviderFactory(dir, Clock.fixed(NOW, ZoneOffset.UTC));
    final Provider provider = factory.create(connection(Map.of()));

    @Test
    void discoveryPagesMapStatusPrivilegeAndReuseOneConnection() {
        dir.users.get("bob").lockoutTime = fileTime(NOW.minusSeconds(600)); // within the 30-minute domain lockout
        dir.users.get("svc-sql").lockoutTime = fileTime(NOW.minusSeconds(7200)); // lockout already expired
        ActiveDirectoryProvider p = (ActiveDirectoryProvider) factory.create(connection(Map.of("pageSize", "10")));
        List<AccountState> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            OperationResult<Page<AccountState>> r = p.discoverAccounts(ctx(), cursor);
            assertTrue(r.isSuccess(), r.toString());
            all.addAll(r.value().orElseThrow().items());
            cursor = r.value().orElseThrow().nextCursor();
            pages++;
        } while (cursor != null);
        p.close();
        assertEquals(1, pages);
        assertEquals(5, all.size());
        Map<String, AccountState> by = new LinkedHashMap<>();
        all.forEach(a -> by.put(a.account().name(), a));
        assertTrue(by.get("Administrator").privileged());
        assertEquals("member of Domain Admins", by.get("alice").attributes().get("privilegeReason"));
        assertEquals(NativeAccountStatus.LOCKED, by.get("bob").status());
        assertEquals("Finance, Team", by.get("bob").groups().get(0).name());
        assertEquals(NativeAccountStatus.ENABLED, by.get("svc-sql").status());
        assertEquals("true", by.get("svc-sql").attributes().get("passwordNeverExpires"));
        assertEquals(NativeAccountStatus.DISABLED, by.get("krbtgt").status());
        assertTrue(AdValues.isGuid(by.get("alice").account().nativeId()));
        assertNotNull(by.get("alice").lastLogin());
        assertEquals(1, dir.opens, "one bind per provider instance");
    }

    @Test
    void pagingFollowsTheServerCookie() {
        for (int i = 0; i < 25; i++) {
            dir.add(new FakeDirectory.User("user" + i, "OU=Staff"));
        }
        ActiveDirectoryProvider p = (ActiveDirectoryProvider) factory.create(connection(Map.of()));
        int total = 0;
        int pages = 0;
        String cursor = null;
        do {
            Page<AccountState> page = p.discoverAccounts(ctx(), cursor).value().orElseThrow();
            total += page.items().size();
            cursor = page.nextCursor();
            pages++;
        } while (cursor != null);
        assertEquals(30, total);
        assertEquals(3, pages);
        assertEquals("INVALID_CURSOR", p.discoverAccounts(ctx(), "%%%").error().orElseThrow().code());
    }

    @Test
    void disableAndEnableFlipOnlyTheDisableBitAndVerify() {
        FakeDirectory.User svc = dir.users.get("svc-sql");
        OperationResult<AccountState> r = provider.disableAccount(ctx(), new AccountRef(null, "svc-sql"));
        assertEquals(OperationOutcome.SUCCEEDED, r.outcome(), r.toString());
        assertEquals(512 | 0x10000 | 2, svc.uac, "other flags kept");
        assertEquals(NativeAccountStatus.DISABLED, r.value().orElseThrow().status());
        int mods = dir.modifications.size();
        assertEquals(OperationOutcome.SUCCEEDED, provider.disableAccount(ctx(), new AccountRef(svc.guid, "svc-sql")).outcome());
        assertEquals(mods, dir.modifications.size(), "idempotent: no second modify");
        OperationResult<AccountState> e = provider.enableAccount(ctx(), new AccountRef(svc.guid, "svc-sql"));
        assertEquals(OperationOutcome.SUCCEEDED, e.outcome(), e.toString());
        assertEquals(512 | 0x10000, svc.uac);
    }

    @Test
    void unlockClearsLockoutTimeAndVerifiesWithTheComputedFlag() {
        dir.users.get("bob").lockoutTime = fileTime(NOW.minusSeconds(60));
        OperationResult<AccountState> r = provider.unlockAccount(ctx(), new AccountRef(null, "bob"));
        assertEquals(OperationOutcome.SUCCEEDED, r.outcome(), r.toString());
        assertEquals("0", dir.users.get("bob").lockoutTime);
        dir.users.get("bob").lockoutTime = fileTime(NOW.minusSeconds(60));
        dir.ignoreModify = true;
        assertEquals(OperationOutcome.UNKNOWN, provider.unlockAccount(ctx(), new AccountRef(null, "bob")).outcome(),
                "a change that does not read back is UNKNOWN");
    }

    @Test
    void rejectedTimedOutAndProtectedChangesAreClassified() {
        dir.rejectModify = "insufficientAccessRights";
        var denied = provider.disableAccount(ctx(), new AccountRef(null, "bob")).error().orElseThrow();
        assertEquals("PRIVILEGE_MISSING", denied.code());
        assertFalse(denied.changeMayHaveApplied());
        dir.rejectModify = null;
        dir.timeoutModify = true;
        var timeout = provider.disableAccount(ctx(), new AccountRef(null, "bob")).error().orElseThrow();
        assertEquals("OPERATION_TIMEOUT", timeout.code());
        assertTrue(timeout.changeMayHaveApplied(), "a modify without answer may have been applied (core maps it to UNKNOWN)");
        dir.timeoutModify = false;
        int before = dir.modifications.size();
        assertEquals("PROTECTED_ACCOUNT", provider.enableAccount(ctx(), new AccountRef(null, "krbtgt")).error().orElseThrow().code());
        assertEquals(before, dir.modifications.size());
    }

    @Test
    void filterInjectionIsImpossibleAndNamesAreValidated() {
        for (String name : List.of("bob)(objectClass=*", "a*", "x\\y", "a,b", "<GUID=1>", "averyveryverylongaccountname")) {
            OperationResult<AccountState> r = provider.disableAccount(ctx(), new AccountRef(null, name));
            assertTrue(r.error().isPresent(), name);
            assertTrue(List.of("INVALID_ACCOUNT_NAME", "ACCOUNT_NOT_FOUND").contains(r.error().orElseThrow().code()), name);
        }
        assertTrue(dir.modifications.isEmpty());
        assertTrue(dir.filters.stream().noneMatch(f -> f.contains("(objectClass=*)(") || f.contains("a*")), dir.filters.toString());
        assertEquals("bob\\29\\28objectClass=\\2a", AdValues.escapeFilterValue("bob)(objectClass=*"));
        assertEquals("x\\5cy\\00", AdValues.escapeFilterValue("x\\y\0"));
    }

    @Test
    void connectionAuthenticationVaultAndValidationFailuresAreClassified() {
        assertTrue(provider.validateConnection(ctx()).isSuccess());
        dir.refuse = "connect";
        var c = factory.create(connection(Map.of())).getAccountState(ctx(), new AccountRef(null, "bob")).error().orElseThrow();
        assertEquals("CONNECTION_FAILED", c.code());
        assertTrue(c.retryable());
        dir.refuse = "auth";
        var a = factory.create(connection(Map.of())).getAccountState(ctx(), new AccountRef(null, "bob")).error().orElseThrow();
        assertEquals("AUTHENTICATION_FAILED", a.code());
        assertFalse(a.retryable());
        dir.refuse = null;
        OperationContext noVault = new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, NOW.plusSeconds(60), h -> {
            throw new CredentialResolver.SecretsUnavailableException("vault down");
        });
        assertEquals("SECRETS_UNAVAILABLE", factory.create(connection(Map.of())).getAccountState(noVault, new AccountRef(null, "bob"))
                .error().orElseThrow().code());
        assertEquals("ACCOUNT_NOT_FOUND", provider.getAccountState(ctx(), new AccountRef(null, "ghost")).error().orElseThrow().code());
        dir.activeDirectory = false;
        assertEquals("CONFIGURATION_INVALID", factory.create(connection(Map.of())).validateConnection(ctx()).error().orElseThrow().code());
    }

    @Test
    void configurationIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ProviderConnection(UUID.randomUUID(), ActiveDirectoryProvider.TYPE,
                "ldap://dc1.corp.example.org", Map.of("bindDn", "x", "baseDn", BASE), new CredentialHandle("ch_x"))), "plain LDAP refused");
        assertThrows(IllegalArgumentException.class, () -> factory.create(connection(Map.of("baseDn", ""))));
        assertThrows(IllegalArgumentException.class, () -> factory.create(connection(Map.of("accountFilter", "objectClass=user"))));
        assertThrows(IllegalArgumentException.class, () -> factory.create(new ProviderConnection(UUID.randomUUID(), ActiveDirectoryProvider.TYPE,
                "https://dc1", Map.of("bindDn", "x", "baseDn", BASE), new CredentialHandle("ch_x"))));
        factory.create(new ProviderConnection(UUID.randomUUID(), ActiveDirectoryProvider.TYPE, "ldap://dc1.corp.example.org",
                Map.of("bindDn", "x", "baseDn", BASE, "startTls", "true"), new CredentialHandle("ch_x")));
    }

    @Test
    void valueConversions() {
        assertEquals(NOW, AdValues.fileTime(fileTime(NOW)));
        assertNull(AdValues.fileTime("0"));
        assertNull(AdValues.fileTime("9223372036854775807"));
        byte[] g = {0x33, 0x22, 0x11, 0x00, 0x55, 0x44, 0x77, 0x66, (byte) 0x88, (byte) 0x99, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
            (byte) 0xdd, (byte) 0xee, (byte) 0xff};
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", AdValues.guid(g));
        assertEquals("Domain Admins", AdValues.rdnValue("CN=Domain Admins,CN=Users,DC=x"));
        assertTrue(AdValues.isSamAccountName("svc-sql"));
        assertFalse(AdValues.isSamAccountName("a|b"));
    }
}
