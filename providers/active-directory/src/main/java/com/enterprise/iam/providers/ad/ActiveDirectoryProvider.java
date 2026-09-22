package com.enterprise.iam.providers.ad;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Active Directory provider over LDAPS (or LDAP + StartTLS), agentless (G6). Reads user accounts with paged searches,
 * disables/enables by flipping {@code ACCOUNTDISABLE} in {@code userAccountControl}, unlocks by clearing
 * {@code lockoutTime}, and verifies every change by reading the account back (G5).
 *
 * <p>Settings: {@code bindDn} (service account DN or UPN, required), {@code baseDn} (required), {@code startTls}
 * (for {@code ldap://} endpoints), {@code allowInsecureLdap} (lab only: plain LDAP), {@code caCertificatePem} or
 * {@code pinnedCertificateSha256} (trust; default JVM trust store), {@code accountFilter} (extra LDAP filter ANDed with
 * the user filter), {@code privilegedGroups} (comma-separated group names; default: the built-in administrative
 * groups), {@code pageSize} (default 500, max 1000), {@code timeoutSeconds} (default 30).
 *
 * <p>One instance serves one worker execution and keeps a single bound connection (paged-search cookies are
 * connection-bound); the worker closes the instance afterwards.
 */
public final class ActiveDirectoryProvider implements Provider, AutoCloseable {

    public static final ProviderTypeId TYPE = ProviderTypeId.of("active-directory");
    static final String VERSION = "1.0.0";

    static final String USER_FILTER = "(&(objectCategory=person)(objectClass=user))";
    static final String GROUP_FILTER = "(objectCategory=group)";
    /** supportedCapabilities OID announcing an Active Directory DC (LDAP_CAP_ACTIVE_DIRECTORY_OID). */
    static final String AD_CAPABILITY_OID = "1.2.840.113556.1.4.800";
    static final List<String> DEFAULT_PRIVILEGED_GROUPS = List.of("Domain Admins", "Enterprise Admins", "Schema Admins",
            "Administrators", "Account Operators", "Backup Operators", "Server Operators", "Print Operators",
            "Group Policy Creator Owners", "DnsAdmins", "Key Admins", "Enterprise Key Admins");
    static final List<String> ACCOUNT_ATTRIBUTES = List.of("sAMAccountName", "objectGUID", "userPrincipalName", "displayName",
            "userAccountControl", "lockoutTime", "accountExpires", "lastLogonTimestamp", "pwdLastSet", "memberOf", "adminCount",
            "servicePrincipalName");
    /** Constructed attributes are only returned by base-scope reads. */
    static final List<String> READ_ATTRIBUTES = Stream.concat(ACCOUNT_ATTRIBUTES.stream(),
            Stream.of("msDS-User-Account-Control-Computed", "msDS-UserPasswordExpiryTimeComputed")).toList();

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
    private final LdapDirectoryFactory directories;
    private final Clock clock;
    private final LdapDirectoryFactory.Config config;
    private final String baseDn;
    private final String accountFilter;
    private final Set<String> privilegedGroups;
    private final int pageSize;

    private LdapDirectory directory;
    /** Domain lockout duration; null = not read yet, Duration.ZERO = "until an administrator unlocks". */
    private Duration lockoutDuration;

    ActiveDirectoryProvider(ProviderConnection connection, LdapDirectoryFactory directories, Clock clock) {
        this.connection = connection;
        this.directories = directories;
        this.clock = clock;
        URI uri = URI.create(connection.endpoint());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("ldaps") || scheme.equals("ldap")) || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint must look like ldaps://dc.example.org[:636] or ldap://dc.example.org[:389]");
        }
        Map<String, String> s = connection.settings();
        LdapDirectoryFactory.Security security;
        if (scheme.equals("ldaps")) {
            security = LdapDirectoryFactory.Security.LDAPS;
        } else if (isTrue(s.get("startTls"))) {
            security = LdapDirectoryFactory.Security.START_TLS;
        } else if (isTrue(s.get("allowInsecureLdap"))) {
            security = LdapDirectoryFactory.Security.PLAIN;
        } else {
            throw new IllegalArgumentException("ldap:// needs startTls=true (plain LDAP would send the bind password in clear text; "
                    + "allowInsecureLdap=true only for labs)");
        }
        String bindDn = s.get("bindDn");
        if (bindDn == null || bindDn.isBlank()) {
            throw new IllegalArgumentException("setting 'bindDn' (service account DN or UPN) is required");
        }
        this.baseDn = s.get("baseDn");
        if (baseDn == null || baseDn.isBlank() || !baseDn.contains("=")) {
            throw new IllegalArgumentException("setting 'baseDn' (e.g. DC=corp,DC=example,DC=org) is required");
        }
        String extra = s.get("accountFilter");
        if (extra != null && !extra.isBlank()) {
            String f = extra.trim();
            if (!f.startsWith("(") || !f.endsWith(")")) {
                throw new IllegalArgumentException("setting 'accountFilter' must be a parenthesised LDAP filter");
            }
            this.accountFilter = "(&" + USER_FILTER + f + ")";
        } else {
            this.accountFilter = USER_FILTER;
        }
        String groups = s.get("privilegedGroups");
        this.privilegedGroups = (groups == null || groups.isBlank() ? DEFAULT_PRIVILEGED_GROUPS.stream() : Arrays.stream(groups.split(",")))
                .map(String::trim).filter(g -> !g.isEmpty()).map(g -> g.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.pageSize = Math.min(1000, Math.max(10, intSetting(s, "pageSize", 500)));
        int port = uri.getPort() > 0 ? uri.getPort() : security == LdapDirectoryFactory.Security.LDAPS ? 636 : 389;
        this.config = new LdapDirectoryFactory.Config(uri.getHost(), port, security, bindDn.trim(), blankToNull(s.get("caCertificatePem")),
                blankToNull(s.get("pinnedCertificateSha256")), Duration.ofSeconds(Math.max(5, intSetting(s, "timeoutSeconds", 30))));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public void close() {
        if (directory != null) {
            directory.close();
            directory = null;
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    public OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        return withDirectory(ctx, ProviderOperation.VALIDATE_CONNECTION, d -> {
            LdapDirectory.Entry dse = d.rootDse(List.of("dnsHostName", "domainControllerFunctionality", "supportedCapabilities"));
            if (!dse.all("supportedCapabilities").contains(AD_CAPABILITY_OID)) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, new ProviderError("CONFIGURATION_INVALID",
                        "the server is not an Active Directory domain controller", false, false));
            }
            if (d.search(baseDn, LdapDirectory.Scope.BASE, "(objectClass=*)", List.of("1.1"), 1, null).entries().isEmpty()) {
                return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, new ProviderError("CONFIGURATION_INVALID",
                        "baseDn does not exist or is not visible to the service account", false, false));
            }
            String version = "DC " + dse.first("dnsHostName") + ", functional level " + dse.first("domainControllerFunctionality");
            return OperationResult.read(ProviderOperation.VALIDATE_CONNECTION, new ConnectionReport("Active Directory", version, null));
        });
    }

    @Override
    public OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        return withDirectory(ctx, ProviderOperation.DISCOVER, d -> {
            long accounts = count(d, accountFilter, ctx);
            long groups = count(d, GROUP_FILTER, ctx);
            if (accounts < 0 || groups < 0) {
                return OperationResult.timeout(ProviderOperation.DISCOVER, "deadline reached while counting directory objects");
            }
            return OperationResult.read(ProviderOperation.DISCOVER, new DiscoverySummary(accounts, groups, 0));
        });
    }

    @Override
    public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        return withDirectory(ctx, ProviderOperation.DISCOVER_ACCOUNTS, d -> {
            byte[] cookie;
            try {
                cookie = cursor == null ? null : Base64.getUrlDecoder().decode(cursor);
            } catch (IllegalArgumentException e) {
                return OperationResult.failed(ProviderOperation.DISCOVER_ACCOUNTS,
                        new ProviderError("INVALID_CURSOR", "cursor is not a paged-search cookie", false, false));
            }
            Duration lockout = lockoutDuration(d);
            LdapDirectory.SearchPage page = d.search(baseDn, LdapDirectory.Scope.SUBTREE, accountFilter, ACCOUNT_ATTRIBUTES, pageSize, cookie);
            List<AccountState> items = new ArrayList<>(page.entries().size());
            for (LdapDirectory.Entry e : page.entries()) {
                items.add(state(e, lockout));
            }
            String next = page.cookie() == null || page.cookie().length == 0 ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(page.cookie());
            return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(items, next));
        });
    }

    @Override
    public OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        return withDirectory(ctx, ProviderOperation.GET_ACCOUNT_STATE, d -> {
            Read r = read(d, account);
            return r.error() != null ? OperationResult.failed(ProviderOperation.GET_ACCOUNT_STATE, r.error())
                    : OperationResult.read(ProviderOperation.GET_ACCOUNT_STATE, r.state());
        });
    }

    @Override
    public OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        return withDirectory(ctx, ProviderOperation.VERIFY_OPERATION, d -> {
            Read r = read(d, account);
            return r.error() != null ? OperationResult.unknown(ProviderOperation.VERIFY_OPERATION, r.error().message())
                    : OperationResult.read(ProviderOperation.VERIFY_OPERATION, r.state());
        });
    }

    // ------------------------------------------------------------------ changes (verified by read-back)

    @Override
    public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.DISABLE_ACCOUNT,
                r -> (r.uac() & AdValues.UF_ACCOUNTDISABLE) != 0 ? null
                        : new String[] {"userAccountControl", String.valueOf(r.uac() | AdValues.UF_ACCOUNTDISABLE)},
                r -> (r.uac() & AdValues.UF_ACCOUNTDISABLE) != 0, "disabled");
    }

    @Override
    public OperationResult<AccountState> enableAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.ENABLE_ACCOUNT,
                r -> (r.uac() & AdValues.UF_ACCOUNTDISABLE) == 0 ? null
                        : new String[] {"userAccountControl", String.valueOf(r.uac() & ~AdValues.UF_ACCOUNTDISABLE)},
                r -> (r.uac() & AdValues.UF_ACCOUNTDISABLE) == 0, "enabled");
    }

    @Override
    public OperationResult<AccountState> unlockAccount(OperationContext ctx, AccountRef account) {
        return change(ctx, account, ProviderOperation.UNLOCK_ACCOUNT,
                r -> r.lockedOut() || AdValues.parseFlags(r.entry().first("lockoutTime")) != 0 ? new String[] {"lockoutTime", "0"} : null,
                r -> !r.lockedOut(), "not locked out");
    }

    /**
     * Sets a vaulted password through {@code unicodePwd} (quoted UTF-16LE), which AD accepts only over an encrypted
     * connection; verification reads {@code pwdLastSet} back.
     */
    @Override
    public OperationResult<Void> rotatePassword(OperationContext ctx, com.enterprise.iam.provider.spi.model.PasswordChange change) {
        ProviderOperation op = ProviderOperation.ROTATE_PASSWORD;
        if (config.security() == LdapDirectoryFactory.Security.PLAIN) {
            return OperationResult.unsupported(op, "Active Directory accepts password changes only over LDAPS or StartTLS");
        }
        return withDirectory(ctx, op, d -> {
            Read before = read(d, change.account());
            if (before.error() != null) {
                return OperationResult.failed(op, before.error());
            }
            if ("krbtgt".equalsIgnoreCase(before.entry().first("sAMAccountName"))) {
                return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT", "the krbtgt account is managed by the domain", false, false));
            }
            long beforeSet = AdValues.parseFlags(before.entry().first("pwdLastSet"));
            Secret pw;
            try {
                pw = ctx.credentials().redeem(change.newSecret());
            } catch (CredentialResolver.SecretsUnavailableException e) {
                return OperationResult.secretsUnavailable(op);
            }
            try (pw) {
                char[] value = pw.reveal(); // provider credential use: unicodePwd value only, cleared below
                byte[] encoded = ("\"" + new String(value) + "\"").getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
                Arrays.fill(value, '\0');
                try {
                    d.replaceBinary(before.entry().dn(), "unicodePwd", encoded);
                } catch (LdapDirectory.RejectedException e) {
                    String code = "insufficientAccessRights".equalsIgnoreCase(e.resultCode()) ? "PRIVILEGE_MISSING"
                            : "constraintViolation".equalsIgnoreCase(e.resultCode()) ? "PASSWORD_POLICY" : "PROVIDER_REJECTED";
                    return OperationResult.failed(op, new ProviderError(code, "directory rejected the password: " + e.resultCode(), false, false));
                } finally {
                    Arrays.fill(encoded, (byte) 0);
                }
            }
            Read after = read(d, new AccountRef(before.state().account().nativeId(), before.state().account().name()));
            long afterSet = after.error() == null ? AdValues.parseFlags(after.entry().first("pwdLastSet")) : 0;
            if (afterSet <= beforeSet) {
                return OperationResult.unknown(op, "pwdLastSet did not change");
            }
            return OperationResult.succeeded(op, null, new Verification(VerificationMode.READ_BACK, clock.instant(),
                    "pwdLastSet of " + after.state().account().name() + " reads back " + AdValues.fileTime(String.valueOf(afterSet))));
        });
    }

    private OperationResult<AccountState> change(OperationContext ctx, AccountRef account, ProviderOperation op,
                                                 java.util.function.Function<Read, String[]> modification,
                                                 java.util.function.Predicate<Read> expected, String description) {
        return withDirectory(ctx, op, d -> {
            Read before = read(d, account);
            if (before.error() != null) {
                return OperationResult.failed(op, before.error());
            }
            if ("krbtgt".equalsIgnoreCase(before.entry().first("sAMAccountName"))) {
                return OperationResult.failed(op, new ProviderError("PROTECTED_ACCOUNT", "the krbtgt account is managed by the domain", false, false));
            }
            String[] mod = modification.apply(before);
            if (mod != null) {
                try {
                    d.replace(before.entry().dn(), mod[0], mod[1]);
                } catch (LdapDirectory.RejectedException e) {
                    String code = "insufficientAccessRights".equalsIgnoreCase(e.resultCode()) ? "PRIVILEGE_MISSING" : "PROVIDER_REJECTED";
                    return OperationResult.failed(op, new ProviderError(code, "directory rejected the change: " + e.resultCode(), false, false));
                }
            }
            Read after = read(d, new AccountRef(before.state().account().nativeId(), before.state().account().name()));
            if (after.error() != null) {
                return OperationResult.unknown(op, "change sent but the account could not be read back: " + after.error().message());
            }
            if (!expected.test(after)) {
                return OperationResult.unknown(op, "read-back shows " + after.state().status() + " instead of " + description);
            }
            return OperationResult.succeeded(op, after.state(), new Verification(VerificationMode.READ_BACK, clock.instant(),
                    "account " + after.state().account().name() + " reads back " + description));
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Base-scope read of one user (includes constructed attributes). */
    private record Read(LdapDirectory.Entry entry, AccountState state, ProviderError error) {
        long uac() {
            return AdValues.parseFlags(entry.first("userAccountControl"));
        }

        boolean lockedOut() {
            return state.status() == NativeAccountStatus.LOCKED
                    || (AdValues.parseFlags(entry.first("msDS-User-Account-Control-Computed")) & AdValues.UF_LOCKOUT) != 0;
        }
    }

    private Read read(LdapDirectory d, AccountRef ref) throws IOException {
        String base;
        if (AdValues.isGuid(ref.nativeId())) {
            base = "<GUID=" + ref.nativeId() + ">";
        } else if (AdValues.isSamAccountName(ref.name())) {
            List<LdapDirectory.Entry> found = d.search(baseDn, LdapDirectory.Scope.SUBTREE,
                    "(&" + USER_FILTER + "(sAMAccountName=" + AdValues.escapeFilterValue(ref.name()) + "))", List.of("1.1"), 2, null).entries();
            if (found.isEmpty()) {
                return new Read(null, null, new ProviderError("ACCOUNT_NOT_FOUND", "no user named " + ref.name(), false, false));
            }
            base = found.get(0).dn();
        } else {
            return new Read(null, null, new ProviderError("INVALID_ACCOUNT_NAME", "not a valid sAMAccountName or objectGUID", false, false));
        }
        List<LdapDirectory.Entry> entries = d.search(base, LdapDirectory.Scope.BASE, USER_FILTER, READ_ATTRIBUTES, 1, null).entries();
        if (entries.isEmpty()) {
            return new Read(null, null, new ProviderError("ACCOUNT_NOT_FOUND", "no user " + (ref.nativeId() != null ? ref.nativeId() : ref.name()), false, false));
        }
        LdapDirectory.Entry e = entries.get(0);
        return new Read(e, state(e, null), null);
    }

    /**
     * Maps a user entry. With constructed attributes (base reads) the lockout flag is authoritative; in discovery pages the
     * lockout is derived from {@code lockoutTime} and the domain lockout duration.
     */
    AccountState state(LdapDirectory.Entry e, Duration domainLockout) {
        long uac = AdValues.parseFlags(e.first("userAccountControl"));
        String computed = e.first("msDS-User-Account-Control-Computed");
        Instant lockoutTime = AdValues.fileTime(e.first("lockoutTime"));
        boolean locked;
        if (computed != null) {
            locked = (AdValues.parseFlags(computed) & AdValues.UF_LOCKOUT) != 0;
        } else if (lockoutTime == null) {
            locked = false;
        } else {
            locked = domainLockout == null || domainLockout.isZero() || lockoutTime.plus(domainLockout).isAfter(clock.instant());
        }
        Instant expires = AdValues.fileTime(e.first("accountExpires"));
        NativeAccountStatus status;
        if ((uac & AdValues.UF_ACCOUNTDISABLE) != 0) {
            status = NativeAccountStatus.DISABLED;
        } else if (locked) {
            status = NativeAccountStatus.LOCKED;
        } else if (expires != null && expires.isBefore(clock.instant())) {
            status = NativeAccountStatus.EXPIRED;
        } else {
            status = NativeAccountStatus.ENABLED;
        }
        List<GroupRef> groups = new ArrayList<>();
        String privilegedVia = "1".equals(e.first("adminCount")) ? "adminCount=1 (member of a protected group)" : null;
        for (String dn : e.all("memberOf")) {
            String cn = AdValues.rdnValue(dn);
            groups.add(new GroupRef(dn, cn));
            if (privilegedVia == null && privilegedGroups.contains(cn.toLowerCase(Locale.ROOT))) {
                privilegedVia = "member of " + cn;
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("distinguishedName", e.dn());
        putIfPresent(attributes, "userPrincipalName", e.first("userPrincipalName"));
        putIfPresent(attributes, "displayName", e.first("displayName"));
        if ((uac & AdValues.UF_DONT_EXPIRE_PASSWD) != 0) {
            attributes.put("passwordNeverExpires", "true");
        }
        if (!e.all("servicePrincipalName").isEmpty()) {
            attributes.put("servicePrincipalNames", String.valueOf(e.all("servicePrincipalName").size()));
        }
        if (expires != null) {
            attributes.put("accountExpires", expires.toString());
        }
        if (privilegedVia != null) {
            attributes.put("privilegeReason", privilegedVia);
        }
        String sam = e.first("sAMAccountName");
        AccountRef ref = new AccountRef(e.first("objectGUID"), sam != null ? sam : AdValues.rdnValue(e.dn()));
        Instant pwdLastSet = AdValues.fileTime(e.first("pwdLastSet"));
        Instant pwdExpires = AdValues.fileTime(e.first("msDS-UserPasswordExpiryTimeComputed"));
        return new AccountState(ref, status, privilegedVia != null, groups, AdValues.fileTime(e.first("lastLogonTimestamp")),
                pwdLastSet, pwdExpires, attributes);
    }

    /** Domain lockout duration from the domain head (negative 100-ns interval; very large magnitude = until unlocked). */
    private Duration lockoutDuration(LdapDirectory d) throws IOException {
        if (lockoutDuration == null) {
            LdapDirectory.Entry dse = d.rootDse(List.of("defaultNamingContext"));
            String domain = dse.first("defaultNamingContext");
            Duration value = Duration.ZERO;
            if (domain != null) {
                List<LdapDirectory.Entry> head = d.search(domain, LdapDirectory.Scope.BASE, "(objectClass=*)", List.of("lockoutDuration"), 1, null).entries();
                if (!head.isEmpty() && head.get(0).first("lockoutDuration") != null) {
                    long v = Math.abs(AdValues.parseFlags(head.get(0).first("lockoutDuration")));
                    value = v == 0 || v >= Long.MAX_VALUE / 2 ? Duration.ZERO : Duration.ofNanos(Math.multiplyExact(v, 100L));
                }
            }
            lockoutDuration = value;
        }
        return lockoutDuration;
    }

    /** Counts entries with a no-attribute paged search; -1 if the deadline passes. */
    private long count(LdapDirectory d, String filter, OperationContext ctx) throws IOException {
        long n = 0;
        byte[] cookie = null;
        do {
            if (ctx.isExpired(clock.instant())) {
                return -1;
            }
            LdapDirectory.SearchPage p = d.search(baseDn, LdapDirectory.Scope.SUBTREE, filter, List.of("1.1"), 1000, cookie);
            n += p.entries().size();
            cookie = p.cookie() == null || p.cookie().length == 0 ? null : p.cookie();
        } while (cookie != null);
        return n;
    }

    @FunctionalInterface
    private interface DirectoryWork<T> {
        OperationResult<T> run(LdapDirectory d) throws IOException;
    }

    private <T> OperationResult<T> withDirectory(OperationContext ctx, ProviderOperation op, DirectoryWork<T> work) {
        try {
            if (directory == null) {
                if (connection.credential() == null) {
                    return OperationResult.secretsUnavailable(op);
                }
                try (Secret password = ctx.credentials().redeem(connection.credential())) {
                    directory = directories.open(config, password);
                }
            }
            return work.run(directory);
        } catch (CredentialResolver.SecretsUnavailableException e) {
            return OperationResult.secretsUnavailable(op);
        } catch (LdapDirectory.AuthenticationException e) {
            close();
            return OperationResult.failed(op, new ProviderError("AUTHENTICATION_FAILED", e.getMessage(), false, false));
        } catch (LdapDirectory.ConnectionException e) {
            close();
            return OperationResult.failed(op, new ProviderError("CONNECTION_FAILED", e.getMessage(), true, false));
        } catch (LdapDirectory.TimeoutException e) {
            close();
            return OperationResult.failed(op, new ProviderError("OPERATION_TIMEOUT", "directory did not answer in time", true, op.mutating()));
        } catch (LdapDirectory.RejectedException e) {
            return OperationResult.failed(op, new ProviderError("insufficientAccessRights".equalsIgnoreCase(e.resultCode())
                    ? "PRIVILEGE_MISSING" : "PROVIDER_ERROR", "directory returned " + e.resultCode(), false, false));
        } catch (IOException e) {
            close();
            return OperationResult.failed(op, new ProviderError("PROVIDER_ERROR", "LDAP failure: " + e.getClass().getSimpleName(), true, op.mutating()));
        }
    }

    private static void putIfPresent(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static boolean isTrue(String v) {
        return "true".equalsIgnoreCase(v);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int intSetting(Map<String, String> s, String key, int fallback) {
        try {
            return Integer.parseInt(s.getOrDefault(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
