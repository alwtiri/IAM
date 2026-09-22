package com.enterprise.iam.providers.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Linux provider semantics against a scripted SSH host (command construction, parsing, verification, errors). */
class LinuxProviderTest {

    /** A tiny simulated Linux host that understands exactly the commands the provider sends. */
    static final class FakeHost implements SshTransportFactory {
        static final class User {
            final String name;
            final long uid;
            final String groups;
            boolean locked;
            boolean expired;
            boolean noPassword;
            int failures;

            User(String name, long uid, String groups) {
                this.name = name;
                this.uid = uid;
                this.groups = groups;
            }
        }

        final Map<String, User> users = new LinkedHashMap<>();
        final List<String> commands = new ArrayList<>();
        String refuse; // "auth" or "connect"
        boolean faillock = true;

        FakeHost() {
            users.put("root", new User("root", 0, "root"));
            users.put("alice", new User("alice", 1001, "alice wheel"));
            users.put("bob", new User("bob", 1002, "bob"));
            users.put("svc-backup", new User("svc-backup", 998, "svc-backup"));
        }

        @Override
        public SshTransport open(String host, int port, String username, Secret credential, boolean keyAuth, String fp, Duration t)
                throws IOException {
            if ("connect".equals(refuse)) {
                throw new SshTransport.ConnectionException("cannot connect to " + host + ":" + port + " (ConnectException)");
            }
            if ("auth".equals(refuse)) {
                throw new SshTransport.AuthenticationException("authentication or host key verification failed for " + username + "@" + host);
            }
            return new SshTransport() {
                @Override
                public Exec exec(String command, byte[] stdin, Duration timeout) {
                    commands.add(command);
                    return run(command);
                }

                @Override
                public void close() {
                }
            };
        }

        String passwdLine(User u) {
            return u.name + ":x:" + u.uid + ":" + u.uid + ":" + (u.name.equals("alice") ? "Alice Admin,,," : "") + ":/home/" + u.name + ":"
                    + (u.uid < 1000 && u.uid != 0 ? "/usr/sbin/nologin" : "/bin/bash");
        }

        String status(User u) {
            return u.name + " " + (u.locked ? "L" : u.noPassword ? "NP" : "P") + " 2026-01-01 0 99999 7 -1";
        }

        SshTransport.Exec run(String c) {
            if (c.startsWith("getent passwd; echo '@@GROUP@@'")) {
                StringBuilder sb = new StringBuilder();
                users.values().forEach(u -> sb.append(passwdLine(u)).append('\n'));
                sb.append("@@GROUP@@\nroot:x:0:\nwheel:x:10:alice\nalice:x:1001:\nbob:x:1002:\nsvc-backup:x:998:\n@@STATUS@@\n");
                users.values().forEach(u -> sb.append(status(u)).append('\n'));
                sb.append("@@LASTLOG@@\nUsername         Port     From             Latest\n");
                sb.append("root             pts/0    10.0.0.1         Mon Sep 21 10:11:12 +0300 2026\n");
                sb.append("bob                                        **Never logged in**\n");
                return new SshTransport.Exec(0, sb.toString(), "");
            }
            if (c.startsWith("getent passwd '")) {
                String name = c.substring("getent passwd '".length(), c.indexOf('\'', "getent passwd '".length()));
                User u = users.get(name);
                if (u == null) {
                    return new SshTransport.Exec(0, "@@GROUPS@@\n@@STATUS@@\n@@EXPIRE@@\n", "");
                }
                return new SshTransport.Exec(0, passwdLine(u) + "\n@@GROUPS@@\n" + u.groups + "\n@@STATUS@@\n" + status(u)
                        + "\n@@EXPIRE@@\n" + (u.expired ? "Jan 02, 1970" : "never") + "\n", "");
            }
            if (c.startsWith("sudo -n usermod -L -e 1 '")) {
                User u = users.get(name(c));
                u.locked = true;
                u.expired = true;
                return new SshTransport.Exec(0, "", "");
            }
            if (c.startsWith("sudo -n usermod -e '' '")) {
                User u = users.get(name(c));
                u.expired = false;
                if (u.noPassword) {
                    return new SshTransport.Exec(3, "", "usermod: unlocking the user's password would result in a passwordless account.");
                }
                u.locked = false;
                return new SshTransport.Exec(0, "", "");
            }
            if (c.startsWith("if command -v faillock") && c.contains("--reset")) {
                if (!faillock) {
                    return new SshTransport.Exec(0, "NO_TALLY\n", "");
                }
                users.get(name(c)).failures = 0;
                return new SshTransport.Exec(0, "", "");
            }
            if (c.startsWith("if command -v faillock") && c.contains("grep -c")) {
                return new SshTransport.Exec(0, users.get(name(c)).failures + "\n", "");
            }
            if (c.startsWith("uname -sr")) {
                return new SshTransport.Exec(0, "Linux 6.8.0\nUbuntu 24.04 LTS\nSUDO_OK\n", "");
            }
            return new SshTransport.Exec(127, "", "unexpected command");
        }

        /** First single-quoted, non-empty account token of a command. */
        static String name(String c) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([a-z_][a-z0-9_.-]*)'").matcher(c);
            return m.find() ? m.group(1) : null;
        }
    }

    static ProviderConnection connection(Map<String, String> extra) {
        Map<String, String> s = new LinkedHashMap<>(Map.of("username", "svc-iam", "hostKeyFingerprint", "SHA256:abc"));
        s.putAll(extra);
        return new ProviderConnection(UUID.randomUUID(), LinuxProvider.TYPE, "ssh://srv01.example.org:22", s, new CredentialHandle("ch_linux"));
    }

    static OperationContext ctx() {
        return new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, Instant.now().plusSeconds(60),
                h -> Secret.of("-----BEGIN OPENSSH PRIVATE KEY-----\n..."));
    }

    final FakeHost host = new FakeHost();
    final Provider provider = new LinuxProviderFactory(host).create(connection(Map.of()));

    @Test
    void discoveryReadsAccountsGroupsPrivilegeAndLastLogin() {
        OperationResult<com.enterprise.iam.provider.spi.model.Page<AccountState>> r = provider.discoverAccounts(ctx(), null);
        assertTrue(r.isSuccess(), r.toString());
        List<AccountState> items = r.value().orElseThrow().items();
        assertEquals(4, items.size());
        AccountState root = items.get(0);
        assertTrue(root.privileged());
        assertEquals("uid 0", root.attributes().get("privilegeReason"));
        assertNotNull(root.lastLogin());
        AccountState alice = items.get(1);
        assertTrue(alice.privileged());
        assertEquals("member of wheel", alice.attributes().get("privilegeReason"));
        assertEquals("Alice Admin", alice.attributes().get("displayName"));
        AccountState svc = items.get(3);
        assertEquals("true", svc.attributes().get("system"));
        assertEquals("false", svc.attributes().get("interactiveLogin"));
        assertFalse(items.get(2).privileged());
        assertTrue(host.commands.get(0).contains("sudo -n passwd -S -a"));
    }

    @Test
    void disableLocksAndExpiresThenVerifiesAndIsIdempotent() {
        OperationResult<AccountState> r = provider.disableAccount(ctx(), new AccountRef("bob", "bob"));
        assertEquals(OperationOutcome.SUCCEEDED, r.outcome(), r.toString());
        assertEquals(NativeAccountStatus.DISABLED, r.value().orElseThrow().status());
        assertTrue(r.verification().isPresent());
        long changes = host.commands.stream().filter(c -> c.contains("usermod")).count();
        provider.disableAccount(ctx(), new AccountRef("bob", "bob"));
        assertEquals(changes, host.commands.stream().filter(c -> c.contains("usermod")).count(), "no second change");
    }

    @Test
    void enableThatCannotTakeEffectIsUnknown() {
        FakeHost.User bob = host.users.get("bob");
        bob.locked = true;
        bob.noPassword = true;
        OperationResult<AccountState> r = provider.enableAccount(ctx(), new AccountRef("bob", "bob"));
        assertEquals(OperationOutcome.UNKNOWN, r.outcome(), r.toString());
        bob.noPassword = false;
        assertEquals(OperationOutcome.SUCCEEDED, provider.enableAccount(ctx(), new AccountRef("bob", "bob")).outcome());
    }

    @Test
    void unsafeAccountNamesNeverReachTheShell() {
        for (String name : List.of("alice; rm -rf /", "$(id)", "a'b", "Robert", "-rf", "")) {
            OperationResult<AccountState> r = provider.disableAccount(ctx(), new AccountRef(null, name.isEmpty() ? " " : name));
            assertEquals("INVALID_ACCOUNT_NAME", r.error().orElseThrow().code(), name);
        }
        assertTrue(host.commands.isEmpty(), "no command was sent: " + host.commands);
    }

    @Test
    void unlockResetsTheFailureCounterAndVerifies() {
        host.users.get("alice").failures = 5;
        OperationResult<AccountState> r = provider.unlockAccount(ctx(), new AccountRef("alice", "alice"));
        assertEquals(OperationOutcome.SUCCEEDED, r.outcome(), r.toString());
        host.faillock = false;
        assertEquals(OperationOutcome.UNSUPPORTED, provider.unlockAccount(ctx(), new AccountRef("alice", "alice")).outcome());
    }

    @Test
    void connectionAuthenticationAndVaultFailuresAreClassified() {
        host.refuse = "connect";
        var c = provider.getAccountState(ctx(), new AccountRef("bob", "bob")).error().orElseThrow();
        assertEquals("CONNECTION_FAILED", c.code());
        assertTrue(c.retryable());
        host.refuse = "auth";
        var a = provider.getAccountState(ctx(), new AccountRef("bob", "bob")).error().orElseThrow();
        assertEquals("AUTHENTICATION_FAILED", a.code());
        assertFalse(a.retryable());
        host.refuse = null;
        OperationContext noVault = new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, Instant.now().plusSeconds(60),
                h -> {
                    throw new CredentialResolver.SecretsUnavailableException("vault down");
                });
        assertEquals("SECRETS_UNAVAILABLE", provider.getAccountState(noVault, new AccountRef("bob", "bob")).error().orElseThrow().code());
        assertEquals("ACCOUNT_NOT_FOUND", provider.getAccountState(ctx(), new AccountRef("ghost", "ghost")).error().orElseThrow().code());
    }

    @Test
    void validationChecksSudoAndConfigurationIsStrict() {
        assertTrue(provider.validateConnection(ctx()).isSuccess());
        LinuxProviderFactory f = new LinuxProviderFactory(host);
        assertThrows(IllegalArgumentException.class, () -> f.create(new ProviderConnection(UUID.randomUUID(), LinuxProvider.TYPE,
                "ssh://srv01", Map.of("username", "svc-iam"), new CredentialHandle("ch_x"))), "host key fingerprint required");
        assertThrows(IllegalArgumentException.class, () -> f.create(new ProviderConnection(UUID.randomUUID(), LinuxProvider.TYPE,
                "https://srv01", Map.of("username", "svc-iam", "allowUnknownHostKey", "true"), new CredentialHandle("ch_x"))));
    }

    @Test
    void parsersHandleDistributionVariants() {
        assertEquals(Map.of("a", "P", "b", "L", "c", "NP", "d", "L"), LinuxParsers.passwordStatus("a PS 2026-01-01 0 99999 7 -1\n"
                + "b LK 2026-01-01\nc NP\nd L 01/01/2026 0 99999 7 -1\ngarbage"));
        assertEquals(Instant.parse("1970-01-02T00:00:00Z"), LinuxParsers.accountExpiry("Jan 02, 1970"));
        assertEquals(null, LinuxParsers.accountExpiry("never"));
        assertEquals(1, LinuxParsers.lastLogins("Username Port From Latest\nroot pts/0 10.0.0.1 Mon Sep 21 10:11:12 +0300 2026\n"
                + "bob **Never logged in**\n").size());
    }
}
