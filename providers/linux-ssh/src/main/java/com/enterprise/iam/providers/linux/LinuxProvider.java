package com.enterprise.iam.providers.linux;

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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Linux provider over SSH, agentless (G6). Reads accounts with {@code getent}/{@code passwd -S}, disables with
 * {@code usermod -L -e 1} (locks the password and expires the account, which also blocks key-based logins), enables
 * with {@code usermod -e '' -U}, unlocks {@code faillock}/{@code pam_tally2} counters, and verifies every change by
 * reading the account back (G5).
 *
 * <p>Command safety: account names are validated against the POSIX portable user-name pattern and single-quoted; no
 * other external input reaches a command line. Settings: {@code username} (service account), {@code authType}
 * ({@code key} default, or {@code password}), {@code hostKeyFingerprint} ({@code SHA256:...}, required unless
 * {@code allowUnknownHostKey=true} for labs), {@code sudo} (default true), {@code privilegedGroups}
 * (default {@code wheel,sudo,admin,root}), {@code timeoutSeconds} (default 30).
 */
public final class LinuxProvider implements Provider {

    public static final ProviderTypeId TYPE = ProviderTypeId.of("linux-ssh");
    static final String VERSION = "1.0.0";
    static final Pattern USER_NAME = Pattern.compile("^[a-z_][a-z0-9_.-]{0,31}$");

    static final ProviderDescriptor DESCRIPTOR = ProviderDescriptor.builder(TYPE, VERSION)
            .capability(CapabilityDescriptor.supported(Capability.CONNECTION_VALIDATION, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISCOVERY, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_STATE_READ, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_ENABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_UNLOCK, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_CREATE, "provisioning follows with request fulfilment (Phase 4)"))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_DELETE, "deletion is not implemented in provider version " + VERSION))
            .capability(CapabilityDescriptor.unsupported(Capability.PASSWORD_RESET, "password operations follow with credential management"))
            .capability(CapabilityDescriptor.supported(Capability.PASSWORD_ROTATION, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.unsupported(Capability.GROUP_DISCOVERY, "group memberships are reported per account"))
            .build();

    private final ProviderConnection connection;
    private final SshTransportFactory transports;
    private final String host;
    private final int port;
    private final String username;
    private final boolean keyAuth;
    private final String hostKeyFingerprint;
    private final boolean sudo;
    private final Set<String> privilegedGroups;
    private final Duration timeout;

    LinuxProvider(ProviderConnection connection, SshTransportFactory transports) {
        this.connection = connection;
        this.transports = transports;
        URI uri = URI.create(connection.endpoint());
        if (!"ssh".equals(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint must look like ssh://host[:port]");
        }
        Map<String, String> s = connection.settings();
        this.host = uri.getHost();
        this.port = uri.getPort() > 0 ? uri.getPort() : 22;
        this.username = s.get("username");
        if (username == null || !USER_NAME.matcher(username).matches()) {
            throw new IllegalArgumentException("setting 'username' (service account) is required");
        }
        this.keyAuth = !"password".equalsIgnoreCase(s.getOrDefault("authType", "key"));
        String fp = s.get("hostKeyFingerprint");
        boolean allowUnknown = "true".equalsIgnoreCase(s.get("allowUnknownHostKey"));
        if ((fp == null || fp.isBlank()) && !allowUnknown) {
            throw new IllegalArgumentException("setting 'hostKeyFingerprint' (SHA256:...) is required; allowUnknownHostKey=true only for labs");
        }
        this.hostKeyFingerprint = fp == null || fp.isBlank() ? null : fp.trim();
        this.sudo = !"false".equalsIgnoreCase(s.getOrDefault("sudo", "true"));
        this.privilegedGroups = Arrays.stream(s.getOrDefault("privilegedGroups", "wheel,sudo,admin,root").split(","))
                .map(String::trim).filter(g -> !g.isEmpty()).collect(Collectors.toUnmodifiableSet());
        int t;
        try {
            t = Integer.parseInt(s.getOrDefault("timeoutSeconds", "30"));
        } catch (NumberFormatException e) {
            t = 30;
        }
        this.timeout = Duration.ofSeconds(Math.max(5, t));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        return session(ctx, ProviderOperation.VALIDATE_CONNECTION, t -> {
            SshTransport.Exec r = t.exec("uname -sr; (. /etc/os-release 2>/dev/null && echo \"$PRETTY_NAME\") || true; "
                    + (sudo ? "sudo -n true && echo SUDO_OK" : "echo SUDO_OFF"), null, timeout);
            String[] lines = r.stdout().split("\n");
            if (sudo && !r.stdout().contains("SUDO_OK")) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, new ProviderError("PRIVILEGE_MISSING",
                        "service account cannot run sudo non-interactively (sudo -n)", false, false));
            }
            String os = lines.length > 1 ? lines[1].trim() : lines[0].trim();
            return OperationResult.read(ProviderOperation.VALIDATE_CONNECTION, new ConnectionReport("Linux", os, null));
        });
    }

    @Override
    public OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        return session(ctx, ProviderOperation.DISCOVER, t -> {
            SshTransport.Exec r = t.exec("getent passwd | wc -l; getent group | wc -l", null, timeout);
            String[] n = r.stdout().trim().split("\\s+");
            long accounts = n.length > 0 ? parseLong(n[0]) : 0;
            long groups = n.length > 1 ? parseLong(n[1]) : 0;
            return OperationResult.read(ProviderOperation.DISCOVER, new DiscoverySummary(accounts, groups, 0));
        });
    }

    @Override
    public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        return session(ctx, ProviderOperation.DISCOVER_ACCOUNTS, t -> {
            String script = "getent passwd; echo '@@GROUP@@'; getent group; echo '@@STATUS@@'; "
                    + sudoPrefix() + "passwd -S -a 2>/dev/null || true; echo '@@LASTLOG@@'; (lastlog 2>/dev/null || true)";
            SshTransport.Exec r = t.exec(script, null, timeout.multipliedBy(4));
            if (!r.ok()) {
                return OperationResult.failed(ProviderOperation.DISCOVER_ACCOUNTS,
                        new ProviderError("PROVIDER_ERROR", "account listing failed with exit code " + r.exitCode(), true, false));
            }
            Map<String, String> sec = LinuxParsers.sections(r.stdout());
            List<LinuxParsers.PasswdEntry> users = LinuxParsers.passwd(sec.getOrDefault("", ""));
            List<LinuxParsers.GroupEntry> groups = LinuxParsers.groups(sec.getOrDefault("GROUP", ""));
            Map<String, java.util.Set<String>> membership = LinuxParsers.membership(users, groups);
            Map<String, String> status = LinuxParsers.passwordStatus(sec.getOrDefault("STATUS", ""));
            Map<String, Instant> lastLogin = LinuxParsers.lastLogins(sec.getOrDefault("LASTLOG", ""));
            List<AccountState> items = new ArrayList<>();
            for (LinuxParsers.PasswdEntry u : users) {
                items.add(state(u, membership.getOrDefault(u.name(), java.util.Set.of()), status.get(u.name()), null, lastLogin.get(u.name())));
            }
            return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(items, null));
        });
    }

    @Override
    public OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        return session(ctx, ProviderOperation.GET_ACCOUNT_STATE, t -> {
            Read r = read(t, account.name());
            return r.error() != null ? OperationResult.failed(ProviderOperation.GET_ACCOUNT_STATE, r.error())
                    : OperationResult.read(ProviderOperation.GET_ACCOUNT_STATE, r.state());
        });
    }

    @Override
    public OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        return session(ctx, ProviderOperation.VERIFY_OPERATION, t -> {
            Read r = read(t, account.name());
            return r.error() != null ? OperationResult.unknown(ProviderOperation.VERIFY_OPERATION, r.error().message())
                    : OperationResult.read(ProviderOperation.VERIFY_OPERATION, r.state());
        });
    }

    // ------------------------------------------------------------------ changes (verified by read-back)

    @Override
    public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.DISABLE_ACCOUNT,
                q -> sudoPrefix() + "usermod -L -e 1 " + q,
                s -> s.status() == NativeAccountStatus.DISABLED || s.status() == NativeAccountStatus.EXPIRED,
                "locked and expired");
    }

    @Override
    public OperationResult<AccountState> enableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.ENABLE_ACCOUNT,
                // -e '' removes the expiry; -U fails for accounts without a password, which the read-back then reports.
                q -> sudoPrefix() + "usermod -e '' " + q + " && " + sudoPrefix() + "usermod -U " + q,
                s -> s.status() == NativeAccountStatus.ENABLED,
                "unlocked and not expired");
    }

    @Override
    public OperationResult<AccountState> unlockAccount(OperationContext ctx, AccountRef account) {
        return session(ctx, ProviderOperation.UNLOCK_ACCOUNT, t -> {
            String q = quotedUser(account.name());
            if (q == null) {
                return invalidName(ProviderOperation.UNLOCK_ACCOUNT);
            }
            SshTransport.Exec reset = t.exec("if command -v faillock >/dev/null 2>&1; then " + sudoPrefix() + "faillock --user " + q
                    + " --reset; elif command -v pam_tally2 >/dev/null 2>&1; then " + sudoPrefix() + "pam_tally2 --user " + q
                    + " --reset >/dev/null; else echo NO_TALLY; fi", null, timeout);
            if (reset.stdout().contains("NO_TALLY")) {
                return OperationResult.unsupported(ProviderOperation.UNLOCK_ACCOUNT, "neither faillock nor pam_tally2 is installed on this host");
            }
            SshTransport.Exec check = t.exec("if command -v faillock >/dev/null 2>&1; then " + sudoPrefix() + "faillock --user " + q
                    + " | grep -c ' V$' || true; else " + sudoPrefix() + "pam_tally2 --user " + q + " | awk 'NR==2{print $2}'; fi",
                    null, timeout);
            String remaining = check.stdout().trim();
            Read after = read(t, account.name());
            if (after.error() != null) {
                return OperationResult.unknown(ProviderOperation.UNLOCK_ACCOUNT, after.error().message());
            }
            if (!remaining.isEmpty() && !"0".equals(remaining)) {
                return OperationResult.unknown(ProviderOperation.UNLOCK_ACCOUNT, "failure counter still shows " + remaining + " entries");
            }
            return OperationResult.succeeded(ProviderOperation.UNLOCK_ACCOUNT, after.state(),
                    new Verification(VerificationMode.READ_BACK, Instant.now(), "failure counter for " + account.name() + " reads back as 0"));
        });
    }

    /**
     * Sets a new password generated and vaulted by the Core. The password travels on stdin to {@code chpasswd} (never on
     * a command line); verification reads the "last password change" date back.
     */
    @Override
    public OperationResult<Void> rotatePassword(OperationContext ctx, PasswordChange change) {
        ProviderOperation op = ProviderOperation.ROTATE_PASSWORD;
        String name = change.account().name();
        String q = quotedUser(name);
        if (q == null) {
            return invalidName(op);
        }
        if (name.equals(username)) {
            return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT", "the platform's own service account is rotated by the platform credential process only", false, false));
        }
        return session(ctx, op, t -> {
            Read before = read(t, name);
            if (before.error() != null) {
                return OperationResult.failed(op, before.error());
            }
            Secret pw;
            try {
                pw = ctx.credentials().redeem(change.newSecret());
            } catch (CredentialResolver.SecretsUnavailableException e) {
                return OperationResult.secretsUnavailable(op);
            }
            SshTransport.Exec r;
            try (pw) {
                char[] value = pw.reveal(); // provider credential use: new password to chpasswd stdin only, cleared below
                byte[] line = (name + ":" + new String(value) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                java.util.Arrays.fill(value, '\0');
                try {
                    r = t.exec(sudoPrefix() + "chpasswd", line, timeout);
                } finally {
                    java.util.Arrays.fill(line, (byte) 0);
                }
            }
            if (!r.ok()) {
                return OperationResult.failed(op, new ProviderError("PROVIDER_REJECTED", "chpasswd failed with exit code " + r.exitCode(), false, false));
            }
            SshTransport.Exec check = t.exec(sudoPrefix() + "chage -l " + q + " | sed -n 's/^Last password change[^:]*:[[:space:]]*//p'", null, timeout);
            java.time.Instant changed = LinuxParsers.accountExpiry(check.stdout().trim());
            java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            if (changed == null || java.time.LocalDate.ofInstant(changed, java.time.ZoneOffset.UTC).isBefore(today.minusDays(1))) {
                return OperationResult.unknown(op, "password change date reads back as '" + check.stdout().trim() + "'");
            }
            return OperationResult.succeeded(op, null, new Verification(VerificationMode.READ_BACK, Instant.now(),
                    "last password change of " + name + " reads back " + check.stdout().trim()));
        });
    }

    private OperationResult<AccountState> change(OperationContext ctx, AccountRef account, ProviderOperation op,
                                                 java.util.function.Function<String, String> command,
                                                 java.util.function.Predicate<AccountState> expected, String description) {
        return session(ctx, op, t -> {
            String q = quotedUser(account.name());
            if (q == null) {
                return invalidName(op);
            }
            Read before = read(t, account.name());
            if (before.error() != null) {
                return OperationResult.failed(op, before.error());
            }
            if (!expected.test(before.state())) {
                SshTransport.Exec r = t.exec(command.apply(q), null, timeout);
                if (!r.ok() && r.exitCode() != 3) { // 3: usermod -U refused (no password); the read-back decides
                    return OperationResult.failed(op, new ProviderError("PROVIDER_REJECTED",
                            "command failed with exit code " + r.exitCode() + ": " + firstLine(r.stderr()), false, true));
                }
            }
            Read after = read(t, account.name());
            if (after.error() != null) {
                return OperationResult.unknown(op, "change sent but the account could not be read back: " + after.error().message());
            }
            if (!expected.test(after.state())) {
                return OperationResult.unknown(op, "read-back shows " + after.state().status() + " instead of " + description);
            }
            return OperationResult.succeeded(op, after.state(),
                    new Verification(VerificationMode.READ_BACK, Instant.now(), "account " + account.name() + " reads back " + description));
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Read(AccountState state, ProviderError error) {
    }

    private Read read(SshTransport t, String name) throws IOException {
        String q = quotedUser(name);
        if (q == null) {
            return new Read(null, new ProviderError("INVALID_ACCOUNT_NAME", "not a valid Linux user name", false, false));
        }
        SshTransport.Exec r = t.exec("getent passwd " + q + "; echo '@@GROUPS@@'; id -Gn " + q + " 2>/dev/null; echo '@@STATUS@@'; "
                + sudoPrefix() + "passwd -S " + q + " 2>/dev/null; echo '@@EXPIRE@@'; " + sudoPrefix() + "chage -l " + q
                + " 2>/dev/null | sed -n 's/^Account expires[^:]*:[[:space:]]*//p'", null, timeout);
        Map<String, String> sec = LinuxParsers.sections(r.stdout());
        List<LinuxParsers.PasswdEntry> entries = LinuxParsers.passwd(sec.getOrDefault("", ""));
        if (entries.isEmpty()) {
            return new Read(null, new ProviderError("ACCOUNT_NOT_FOUND", "no account named " + name, false, false));
        }
        Set<String> groups = Arrays.stream(sec.getOrDefault("GROUPS", "").trim().split("\\s+")).filter(g -> !g.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        String status = LinuxParsers.passwordStatus(sec.getOrDefault("STATUS", "")).get(name);
        Instant expiry = LinuxParsers.accountExpiry(sec.getOrDefault("EXPIRE", ""));
        return new Read(state(entries.get(0), groups, status, expiry, null), null);
    }

    AccountState state(LinuxParsers.PasswdEntry u, Set<String> groups, String passwordStatus, Instant expiry, Instant lastLogin) {
        NativeAccountStatus status;
        if (expiry != null && expiry.isBefore(Instant.now())) {
            status = "L".equals(passwordStatus) ? NativeAccountStatus.DISABLED : NativeAccountStatus.EXPIRED;
        } else if ("L".equals(passwordStatus)) {
            status = NativeAccountStatus.DISABLED;
        } else if (passwordStatus == null) {
            status = NativeAccountStatus.UNKNOWN;
        } else {
            status = NativeAccountStatus.ENABLED;
        }
        String privilegedVia = u.uid() == 0 ? "uid 0" : groups.stream().filter(privilegedGroups::contains).findFirst()
                .map(g -> "member of " + g).orElse(null);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("uid", String.valueOf(u.uid()));
        attributes.put("gid", String.valueOf(u.gid()));
        attributes.put("home", u.home());
        attributes.put("shell", u.shell());
        attributes.put("system", String.valueOf(u.uid() < 1000));
        String gecos = u.gecos() == null ? "" : u.gecos().split(",", 2)[0].trim();
        if (!gecos.isEmpty()) {
            attributes.put("displayName", gecos);
        }
        if ("NP".equals(passwordStatus)) {
            attributes.put("noPassword", "true");
        }
        if (u.shell().endsWith("nologin") || u.shell().endsWith("/false")) {
            attributes.put("interactiveLogin", "false");
        }
        if (privilegedVia != null) {
            attributes.put("privilegeReason", privilegedVia);
        }
        List<GroupRef> refs = new ArrayList<>();
        groups.forEach(g -> refs.add(new GroupRef(g, g)));
        return new AccountState(new AccountRef(u.name(), u.name()), status, privilegedVia != null, refs, lastLogin, null, expiry, attributes);
    }

    @FunctionalInterface
    private interface SessionWork<T> {
        OperationResult<T> run(SshTransport t) throws IOException;
    }

    private <T> OperationResult<T> session(OperationContext ctx, ProviderOperation op, SessionWork<T> work) {
        if (connection.credential() == null) {
            return OperationResult.secretsUnavailable(op);
        }
        Secret credential;
        try {
            credential = ctx.credentials().redeem(connection.credential());
        } catch (CredentialResolver.SecretsUnavailableException e) {
            return OperationResult.secretsUnavailable(op);
        }
        try (credential; SshTransport t = transports.open(host, port, username, credential, keyAuth, hostKeyFingerprint, timeout)) {
            return work.run(t);
        } catch (SshTransport.AuthenticationException e) {
            return OperationResult.failed(op, new ProviderError("AUTHENTICATION_FAILED", e.getMessage(), false, false));
        } catch (SshTransport.ConnectionException e) {
            return OperationResult.failed(op, new ProviderError("CONNECTION_FAILED", e.getMessage(), true, false));
        } catch (IOException e) {
            return OperationResult.failed(op, new ProviderError("OPERATION_TIMEOUT", "SSH command did not complete: " + e.getMessage(),
                    true, op.mutating()));
        }
    }

    private String sudoPrefix() {
        return sudo ? "sudo -n " : "";
    }

    /** Validated and single-quoted account name, or null if the name is not a portable Linux user name. */
    static String quotedUser(String name) {
        return name != null && USER_NAME.matcher(name).matches() ? "'" + name + "'" : null;
    }

    private static <T> OperationResult<T> invalidName(ProviderOperation op) {
        return OperationResult.failed(op, new ProviderError("INVALID_ACCOUNT_NAME", "not a valid Linux user name", false, false));
    }

    private static String firstLine(String s) {
        String line = s == null ? "" : s.strip().split("\n", 2)[0];
        return line.length() > 200 ? line.substring(0, 200) : line.toLowerCase(Locale.ROOT).contains("password") ? "(redacted)" : line;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
