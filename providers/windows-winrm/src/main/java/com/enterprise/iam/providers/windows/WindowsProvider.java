package com.enterprise.iam.providers.windows;

import com.enterprise.iam.kernel.Json;
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
import com.enterprise.iam.provider.spi.result.OperationResult;
import com.enterprise.iam.provider.spi.result.ProviderError;
import com.enterprise.iam.provider.spi.result.Verification;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
 * Windows provider for local accounts over WinRM (HTTPS, PowerShell LocalAccounts module), agentless (G6). Disables and
 * enables with {@code Disable-/Enable-LocalUser}, clears lockouts through ADSI, and verifies every change by reading the
 * account back (G5).
 *
 * <p>Script safety: all scripts are constants; account names and SIDs travel as base64-encoded JSON parameters and are
 * validated before use. Settings: {@code username} (service account, member of Administrators; for non-built-in local
 * admins LocalAccountTokenFilterPolicy must allow remote admin tokens), {@code pinnedCertificateSha256} or
 * {@code caCertificatePem} (listener trust; default JVM trust store), {@code privilegedGroupSids} (default: Administrators,
 * Backup, Power Users, Account and Server Operators, Hyper-V Administrators), {@code timeoutSeconds} (default 60).
 */
public final class WindowsProvider implements Provider {

    public static final ProviderTypeId TYPE = ProviderTypeId.of("windows-winrm");
    static final String VERSION = "1.0.0";
    static final Pattern NAME = Pattern.compile("^[^\"/\\\\\\[\\]:;|=,+*?<>@\\p{Cntrl}]{1,20}$");
    static final Pattern SID = Pattern.compile("^S-1-[0-9]+(-[0-9]+){1,14}$");
    static final List<String> DEFAULT_PRIVILEGED_SIDS = List.of("S-1-5-32-544", "S-1-5-32-551", "S-1-5-32-547", "S-1-5-32-548",
            "S-1-5-32-549", "S-1-5-32-578");

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

    /** Shared prologue: group memberships by member SID, lockout flags, and a function that maps a LocalUser. */
    static final String COMMON = """
            $byUser=@{}
            foreach($g in Get-LocalGroup){ $ms=@(); try{ $ms=@(Get-LocalGroupMember -SID $g.SID -ErrorAction Stop) }catch{}
              foreach($m in $ms){ $k=$m.SID.Value; if(-not $byUser.ContainsKey($k)){ $byUser[$k]=New-Object System.Collections.ArrayList }
                [void]$byUser[$k].Add(@{name=$g.Name;sid=$g.SID.Value}) } }
            $locked=@{}; foreach($a in Get-CimInstance Win32_UserAccount -Filter 'LocalAccount=True'){ $locked[$a.SID]=[bool]$a.Lockout }
            function D($x){ if($x){ $x.ToUniversalTime().ToString('o') } else { $null } }
            function U($u){ $s=$u.SID.Value
              @{name=$u.Name;sid=$s;enabled=[bool]$u.Enabled;fullName=$u.FullName;description=$u.Description;
                lastLogon=(D $u.LastLogon);passwordLastSet=(D $u.PasswordLastSet);passwordExpires=(D $u.PasswordExpires);
                accountExpires=(D $u.AccountExpires);locked=[bool]$locked[$s];
                groups=$(if($byUser.ContainsKey($s)){ @($byUser[$s]) } else { @() })} }
            """;
    static final String DISCOVER_ACCOUNTS = COMMON + "ConvertTo-Json -InputObject @(foreach($u in Get-LocalUser){ U $u }) -Depth 5 -Compress";
    static final String READ = COMMON + """
            $u=$null; try{ if($p.sid){ $u=Get-LocalUser -SID $p.sid } else { $u=Get-LocalUser -Name $p.name } }catch{}
            if($u){ ConvertTo-Json -InputObject (U $u) -Depth 5 -Compress } else { 'null' }""";
    static final String VALIDATE = """
            $id=[Security.Principal.WindowsIdentity]::GetCurrent()
            $admin=(New-Object Security.Principal.WindowsPrincipal $id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
            ConvertTo-Json -Compress -InputObject @{os=(Get-CimInstance Win32_OperatingSystem).Caption;ps=$PSVersionTable.PSVersion.ToString();
              admin=$admin;localAccounts=[bool](Get-Command Get-LocalUser -ErrorAction SilentlyContinue)}""";
    static final String SUMMARY = "ConvertTo-Json -Compress -InputObject @{accounts=@(Get-LocalUser).Count;groups=@(Get-LocalGroup).Count}";
    static final String DISABLE = "Disable-LocalUser -SID $p.sid";
    static final String ENABLE = "Enable-LocalUser -SID $p.sid";
    static final String ROTATE = "Set-LocalUser -SID $p.sid -Password (ConvertTo-SecureString $p.password -AsPlainText -Force)";
    static final String UNLOCK = "$n=(Get-LocalUser -SID $p.sid).Name; $a=[ADSI](\"WinNT://./\"+$n+\",user\"); $a.IsAccountLocked=$false; $a.SetInfo()";

    private final ProviderConnection connection;
    private final WinRmTransport transport;
    private final Clock clock;
    private final WinRmTransport.Endpoint endpoint;
    private final String serviceAccount;
    private final Set<String> privilegedSids;

    WindowsProvider(ProviderConnection connection, WinRmTransport transport, Clock clock) {
        this.connection = connection;
        this.transport = transport;
        this.clock = clock;
        URI uri = URI.create(connection.endpoint());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint must look like https://host:5986/wsman (HTTP is refused: Basic authentication would expose the password)");
        }
        String url = uri.getPath() == null || uri.getPath().isBlank() || "/".equals(uri.getPath())
                ? "https://" + uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 5986) + "/wsman" : uri.toString();
        Map<String, String> s = connection.settings();
        this.serviceAccount = s.get("username");
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw new IllegalArgumentException("setting 'username' (service account) is required");
        }
        String groups = s.get("privilegedGroupSids");
        this.privilegedSids = (groups == null || groups.isBlank() ? DEFAULT_PRIVILEGED_SIDS.stream() : Arrays.stream(groups.split(",")))
                .map(String::trim).filter(g -> !g.isEmpty()).collect(Collectors.toUnmodifiableSet());
        int t;
        try {
            t = Integer.parseInt(s.getOrDefault("timeoutSeconds", "60").trim());
        } catch (NumberFormatException e) {
            t = 60;
        }
        this.endpoint = new WinRmTransport.Endpoint(url, serviceAccount.trim(), blank(s.get("caCertificatePem")), blank(s.get("pinnedCertificateSha256")),
                Duration.ofSeconds(Math.max(10, t)));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        return call(ctx, ProviderOperation.VALIDATE_CONNECTION, r -> {
            WinRmTransport.Result res = r.run(VALIDATE, "{}");
            if (!res.ok()) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, scriptError(res, false));
            }
            Map<String, Object> m = Json.parseObject(res.stdout().trim());
            if (!Boolean.TRUE.equals(m.get("localAccounts"))) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, new ProviderError("CONFIGURATION_INVALID",
                        "the PowerShell LocalAccounts module is not available (Windows Server 2016 or later is required)", false, false));
            }
            if (!Boolean.TRUE.equals(m.get("admin"))) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, new ProviderError("PRIVILEGE_MISSING",
                        "the service account has no administrator token over WinRM (Administrators membership; LocalAccountTokenFilterPolicy=1 "
                                + "for non-built-in local accounts)", false, false));
            }
            return OperationResult.read(ProviderOperation.VALIDATE_CONNECTION,
                    new ConnectionReport("Windows", m.get("os") + " (PowerShell " + m.get("ps") + ")", null));
        });
    }

    @Override
    public OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        return call(ctx, ProviderOperation.DISCOVER, r -> {
            WinRmTransport.Result res = r.run(SUMMARY, "{}");
            if (!res.ok()) {
                return OperationResult.failed(ProviderOperation.DISCOVER, scriptError(res, false));
            }
            Map<String, Object> m = Json.parseObject(res.stdout().trim());
            return OperationResult.read(ProviderOperation.DISCOVER, new DiscoverySummary(num(m.get("accounts")), num(m.get("groups")), 0));
        });
    }

    @Override
    public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        return call(ctx, ProviderOperation.DISCOVER_ACCOUNTS, r -> {
            WinRmTransport.Result res = r.run(DISCOVER_ACCOUNTS, "{}");
            if (!res.ok()) {
                return OperationResult.failed(ProviderOperation.DISCOVER_ACCOUNTS, scriptError(res, false));
            }
            List<AccountState> items = new ArrayList<>();
            if (Json.parse(res.stdout().trim()) instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        items.add(state(m));
                    }
                }
            }
            return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(items, null));
        });
    }

    @Override
    public OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        return call(ctx, ProviderOperation.GET_ACCOUNT_STATE, r -> {
            Read rd = read(r, account);
            return rd.error() != null ? OperationResult.failed(ProviderOperation.GET_ACCOUNT_STATE, rd.error())
                    : OperationResult.read(ProviderOperation.GET_ACCOUNT_STATE, rd.state());
        });
    }

    @Override
    public OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        return call(ctx, ProviderOperation.VERIFY_OPERATION, r -> {
            Read rd = read(r, account);
            return rd.error() != null ? OperationResult.unknown(ProviderOperation.VERIFY_OPERATION, rd.error().message())
                    : OperationResult.read(ProviderOperation.VERIFY_OPERATION, rd.state());
        });
    }

    // ------------------------------------------------------------------ changes (verified by read-back)

    @Override
    public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.DISABLE_ACCOUNT, DISABLE, s -> s.status() == NativeAccountStatus.DISABLED, "disabled");
    }

    @Override
    public OperationResult<AccountState> enableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.ENABLE_ACCOUNT, ENABLE, s -> !"false".equals(s.attributes().get("enabled")), "enabled");
    }

    @Override
    public OperationResult<AccountState> unlockAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.UNLOCK_ACCOUNT, UNLOCK, s -> !"true".equals(s.attributes().get("locked")), "not locked out");
    }

    /**
     * Sets a new password generated and vaulted by the Core (LAPS-style rotation, including the built-in Administrator).
     * The password travels base64-encoded in the HTTPS body, never in the script text; verification reads PasswordLastSet.
     */
    @Override
    public OperationResult<Void> rotatePassword(OperationContext ctx, com.enterprise.iam.provider.spi.model.PasswordChange change) {
        ProviderOperation op = ProviderOperation.ROTATE_PASSWORD;
        if (!validRef(change.account())) {
            return OperationResult.failed(op, invalidName());
        }
        return call(ctx, op, r -> {
            Read before = read(r, change.account());
            if (before.error() != null) {
                return OperationResult.failed(op, before.error());
            }
            if (before.state().account().name().equalsIgnoreCase(accountName(serviceAccount))) {
                return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT",
                        "the platform's own service account is not rotated through account operations", false, false));
            }
            String sid = before.state().account().nativeId();
            Instant started = clock.instant();
            Secret pw;
            try {
                pw = ctx.credentials().redeem(change.newSecret());
            } catch (CredentialResolver.SecretsUnavailableException e) {
                return OperationResult.secretsUnavailable(op);
            }
            WinRmTransport.Result res;
            try (pw) {
                char[] value = pw.reveal(); // provider credential use: new password in the encrypted request body only
                String params = Json.write(Map.of("sid", sid, "password", new String(value)));
                java.util.Arrays.fill(value, '\0');
                res = r.run(ROTATE, params);
            }
            if (!res.ok()) {
                return OperationResult.failed(op, scriptError(res, false));
            }
            Read after = read(r, new AccountRef(sid, before.state().account().name()));
            Instant set = after.state() == null ? null : after.state().passwordLastSet();
            if (set == null || set.isBefore(started.minusSeconds(120))) {
                return OperationResult.unknown(op, "PasswordLastSet did not change");
            }
            return OperationResult.succeeded(op, null, new Verification(VerificationMode.READ_BACK, clock.instant(),
                    "PasswordLastSet of " + before.state().account().name() + " reads back " + set));
        });
    }

    private OperationResult<AccountState> change(OperationContext ctx, AccountRef account, ProviderOperation op, String script,
                                                 java.util.function.Predicate<AccountState> expected, String description) {
        if (!validRef(account)) {
            return OperationResult.failed(op, invalidName());
        }
        return call(ctx, op, r -> {
            Read before = read(r, account);
            if (before.error() != null) {
                return OperationResult.failed(op, before.error());
            }
            String sid = before.state().account().nativeId();
            if (sid.endsWith("-500") || before.state().account().name().equalsIgnoreCase(accountName(serviceAccount))) {
                return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT",
                        "the built-in Administrator and the platform's own service account are not changed by the platform", false, false));
            }
            if (!expected.test(before.state())) {
                WinRmTransport.Result res = r.run(script, Json.write(Map.of("sid", sid)));
                if (!res.ok()) {
                    return OperationResult.failed(op, scriptError(res, true));
                }
            }
            Read after = read(r, new AccountRef(sid, before.state().account().name()));
            if (after.error() != null) {
                return OperationResult.unknown(op, "change sent but the account could not be read back: " + after.error().message());
            }
            if (!expected.test(after.state())) {
                return OperationResult.unknown(op, "read-back shows " + after.state().status() + " instead of " + description);
            }
            return OperationResult.succeeded(op, after.state(), new Verification(VerificationMode.READ_BACK, clock.instant(),
                    "account " + after.state().account().name() + " reads back " + description));
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Read(AccountState state, ProviderError error) {
    }

    private Read read(Runner r, AccountRef ref) throws IOException {
        if (!validRef(ref)) {
            return new Read(null, invalidName());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (ref.nativeId() != null && SID.matcher(ref.nativeId()).matches()) {
            params.put("sid", ref.nativeId());
        } else {
            params.put("name", ref.name());
        }
        WinRmTransport.Result res = r.run(READ, Json.write(params));
        if (!res.ok()) {
            return new Read(null, scriptError(res, false));
        }
        Object parsed = Json.parse(res.stdout().trim());
        if (!(parsed instanceof Map<?, ?> m)) {
            return new Read(null, new ProviderError("ACCOUNT_NOT_FOUND", "no local account " + ref.name(), false, false));
        }
        return new Read(state(m), null);
    }

    AccountState state(Map<?, ?> m) {
        String sid = str(m.get("sid"));
        String name = str(m.get("name"));
        boolean enabled = Boolean.TRUE.equals(m.get("enabled"));
        boolean locked = Boolean.TRUE.equals(m.get("locked"));
        Instant expires = instant(m.get("accountExpires"));
        NativeAccountStatus status = !enabled ? NativeAccountStatus.DISABLED : locked ? NativeAccountStatus.LOCKED
                : expires != null && expires.isBefore(clock.instant()) ? NativeAccountStatus.EXPIRED : NativeAccountStatus.ENABLED;
        List<GroupRef> groups = new ArrayList<>();
        String privilegedVia = null;
        if (m.get("groups") instanceof List<?> gs) {
            for (Object o : gs) {
                if (o instanceof Map<?, ?> g && g.get("name") != null) {
                    String gsid = str(g.get("sid"));
                    groups.add(new GroupRef(gsid, str(g.get("name"))));
                    if (privilegedVia == null && gsid != null && privilegedSids.contains(gsid)) {
                        privilegedVia = "member of " + g.get("name");
                    }
                }
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("sid", sid);
        attributes.put("enabled", String.valueOf(enabled));
        attributes.put("locked", String.valueOf(locked));
        String fullName = str(m.get("fullName"));
        if (fullName != null && !fullName.isBlank()) {
            attributes.put("displayName", fullName);
        }
        String description = str(m.get("description"));
        if (description != null && !description.isBlank()) {
            attributes.put("description", description.length() > 200 ? description.substring(0, 200) : description);
        }
        if (sid != null && (sid.endsWith("-500") || sid.endsWith("-501") || sid.endsWith("-503") || sid.endsWith("-504"))) {
            attributes.put("builtIn", "true");
        }
        if (m.get("passwordExpires") == null && enabled) {
            attributes.put("passwordNeverExpires", "true");
        }
        if (privilegedVia != null) {
            attributes.put("privilegeReason", privilegedVia);
        }
        return new AccountState(new AccountRef(sid, name), status, privilegedVia != null, groups, instant(m.get("lastLogon")),
                instant(m.get("passwordLastSet")), instant(m.get("passwordExpires")), attributes);
    }

    @FunctionalInterface
    private interface Runner {
        WinRmTransport.Result run(String script, String parametersJson) throws IOException;
    }

    @FunctionalInterface
    private interface Work<T> {
        OperationResult<T> run(Runner r) throws IOException;
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
        try (password) {
            return work.run((script, params) -> transport.run(endpoint, password, script, params));
        } catch (WinRmTransport.AuthenticationException e) {
            return OperationResult.failed(op, new ProviderError("AUTHENTICATION_FAILED", e.getMessage(), false, false));
        } catch (WinRmTransport.ConnectionException e) {
            return OperationResult.failed(op, new ProviderError("CONNECTION_FAILED", e.getMessage(), true, false));
        } catch (IOException e) {
            return OperationResult.failed(op, new ProviderError("PROVIDER_ERROR", "WinRM failure: " + e.getMessage(), true, op.mutating()));
        } catch (RuntimeException e) {
            return OperationResult.failed(op, new ProviderError("PROVIDER_ERROR", "unexpected WinRM output (" + e.getClass().getSimpleName() + ")",
                    false, op.mutating()));
        }
    }

    private static ProviderError scriptError(WinRmTransport.Result res, boolean changeMayHaveApplied) {
        String msg = res.stderr() == null ? "" : res.stderr().strip();
        msg = msg.isEmpty() ? "exit code " + res.exitCode() : msg.split("\n", 2)[0];
        if (msg.length() > 200) {
            msg = msg.substring(0, 200);
        }
        String code = msg.toLowerCase(Locale.ROOT).contains("access is denied") ? "PRIVILEGE_MISSING" : "PROVIDER_REJECTED";
        return new ProviderError(code, "PowerShell reported: " + msg, false, changeMayHaveApplied);
    }

    private static boolean validRef(AccountRef ref) {
        return (ref.nativeId() != null && SID.matcher(ref.nativeId()).matches()) || (ref.name() != null && NAME.matcher(ref.name()).matches()
                && !ref.name().isBlank() && !ref.name().endsWith("."));
    }

    private static ProviderError invalidName() {
        return new ProviderError("INVALID_ACCOUNT_NAME", "not a valid local account name or SID", false, false);
    }

    private static String accountName(String user) {
        int i = user.lastIndexOf('\\');
        return i >= 0 ? user.substring(i + 1) : user;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(o.toString());
        } catch (DateTimeParseException e) {
            try {
                return java.time.OffsetDateTime.parse(o.toString()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
