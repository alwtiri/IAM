package com.enterprise.iam.providers.genericrest;

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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/**
 * Generic application provider over SCIM 2.0 (RFC 7643/7644). Reads users (paged), enables and disables them by
 * patching {@code active}, and verifies every change by reading the user back (G5). Password operations, unlock and
 * provisioning are declared UNSUPPORTED with a reason rather than approximated.
 *
 * <p>Settings: {@code authType} = {@code bearer} (default) or {@code basic}; {@code username} (basic only, not secret);
 * {@code pageSize} (default 100, max 500); {@code privilegedGroups} (comma-separated group display names treated as
 * privileged); {@code timeoutSeconds} (default 20). The credential (token or password) arrives as a handle.
 */
public final class ScimProvider implements Provider {

    public static final ProviderTypeId TYPE = ProviderTypeId.of("generic-rest");
    static final String VERSION = "1.0.0";
    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    static final ProviderDescriptor DESCRIPTOR = ProviderDescriptor.builder(TYPE, VERSION)
            .capability(CapabilityDescriptor.supported(Capability.CONNECTION_VALIDATION, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISCOVERY, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_STATE_READ, null))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_ENABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.supported(Capability.ACCOUNT_DISABLE, VerificationMode.READ_BACK))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_UNLOCK, "SCIM 2.0 has no account lockout attribute"))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_CREATE, "provisioning is not implemented in provider version " + VERSION))
            .capability(CapabilityDescriptor.unsupported(Capability.ACCOUNT_DELETE, "deletion is not implemented in provider version " + VERSION))
            .capability(CapabilityDescriptor.unsupported(Capability.PASSWORD_RESET, "password operations are not implemented in provider version " + VERSION))
            .capability(CapabilityDescriptor.unsupported(Capability.PASSWORD_ROTATION, "password operations are not implemented in provider version " + VERSION))
            .capability(CapabilityDescriptor.unsupported(Capability.GROUP_DISCOVERY, "group memberships are reported per user; separate group discovery follows"))
            .build();

    private final ProviderConnection connection;
    private final HttpClient http;
    private final URI base;
    private final int pageSize;
    private final Set<String> privilegedGroups;
    private final Duration timeout;

    ScimProvider(ProviderConnection connection, HttpClient http) {
        this.connection = connection;
        this.http = http;
        String endpoint = connection.endpoint().endsWith("/") ? connection.endpoint() : connection.endpoint() + "/";
        this.base = URI.create(endpoint);
        this.pageSize = Math.max(1, Math.min(500, intSetting("pageSize", 100)));
        this.timeout = Duration.ofSeconds(Math.max(1, intSetting("timeoutSeconds", 20)));
        String groups = connection.settings().getOrDefault("privilegedGroups", "");
        this.privilegedGroups = Arrays.stream(groups.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        Call c = call(ctx, "GET", "ServiceProviderConfig", null);
        if (c.error() != null) {
            return OperationResult.failed(ProviderOperation.VALIDATE_CONNECTION, c.error());
        }
        return OperationResult.read(ProviderOperation.VALIDATE_CONNECTION, new ConnectionReport("SCIM 2.0", "2.0", null));
    }

    @Override
    public OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        Call c = call(ctx, "GET", "Users?startIndex=1&count=1", null);
        if (c.error() != null) {
            return OperationResult.failed(ProviderOperation.DISCOVER, c.error());
        }
        long total = c.json().get("totalResults") instanceof Number n ? n.longValue() : 0;
        return OperationResult.read(ProviderOperation.DISCOVER, new DiscoverySummary(total, 0, 0));
    }

    @Override
    public OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        int start = cursor == null ? 1 : Integer.parseInt(cursor);
        Call c = call(ctx, "GET", "Users?startIndex=" + start + "&count=" + pageSize, null);
        if (c.error() != null) {
            return OperationResult.failed(ProviderOperation.DISCOVER_ACCOUNTS, c.error());
        }
        List<AccountState> items = new ArrayList<>();
        if (c.json().get("Resources") instanceof List<?> resources) {
            for (Object r : resources) {
                if (r instanceof Map<?, ?> user) {
                    items.add(state(user));
                }
            }
        }
        long total = c.json().get("totalResults") instanceof Number n ? n.longValue() : items.size();
        int next = start + items.size();
        String nextCursor = items.isEmpty() || next > total ? null : String.valueOf(next);
        return OperationResult.read(ProviderOperation.DISCOVER_ACCOUNTS, new Page<>(items, nextCursor));
    }

    @Override
    public OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        Lookup l = lookup(ctx, account);
        return l.error() != null ? OperationResult.failed(ProviderOperation.GET_ACCOUNT_STATE, l.error())
                : OperationResult.read(ProviderOperation.GET_ACCOUNT_STATE, state(l.user()));
    }

    @Override
    public OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        Lookup l = lookup(ctx, account);
        return l.error() != null ? OperationResult.unknown(ProviderOperation.VERIFY_OPERATION, l.error().message())
                : OperationResult.read(ProviderOperation.VERIFY_OPERATION, state(l.user()));
    }

    // ------------------------------------------------------------------ changes (verified by read-back)

    @Override
    public OperationResult<AccountState> enableAccount(OperationContext ctx, AccountRef account) {
        return setActive(ctx, account, true, ProviderOperation.ENABLE_ACCOUNT);
    }

    @Override
    public OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        return setActive(ctx, account, false, ProviderOperation.DISABLE_ACCOUNT);
    }

    private OperationResult<AccountState> setActive(OperationContext ctx, AccountRef account, boolean active, ProviderOperation op) {
        Lookup before = lookup(ctx, account);
        if (before.error() != null) {
            return OperationResult.failed(op, before.error());
        }
        String id = String.valueOf(before.user().get("id"));
        if (!Boolean.valueOf(active).equals(before.user().get("active"))) {
            Map<String, Object> patch = Map.of("schemas", List.of(PATCH_OP),
                    "Operations", List.of(Map.of("op", "replace", "path", "active", "value", active)));
            Call c = call(ctx, "PATCH", "Users/" + encodePath(id), Json.write(patch));
            if (c.error() != null) {
                // A 5xx or a timeout after sending may have applied the change: the Core will record UNKNOWN.
                return OperationResult.failed(op, c.error());
            }
        }
        Lookup after = lookup(ctx, new AccountRef(id, account.name()));
        if (after.error() != null) {
            return OperationResult.unknown(op, "change sent but the account could not be read back: " + after.error().message());
        }
        AccountState state = state(after.user());
        NativeAccountStatus expected = active ? NativeAccountStatus.ENABLED : NativeAccountStatus.DISABLED;
        if (state.status() != expected) {
            return OperationResult.unknown(op, "read-back shows " + state.status() + " instead of " + expected);
        }
        return OperationResult.succeeded(op, state, new Verification(VerificationMode.READ_BACK, Instant.now(),
                "SCIM user " + account.name() + " reads back active=" + active));
    }

    // ------------------------------------------------------------------ helpers

    private record Lookup(Map<?, ?> user, ProviderError error) {
    }

    private Lookup lookup(OperationContext ctx, AccountRef account) {
        if (account.nativeId() != null && !account.nativeId().isBlank()) {
            Call c = call(ctx, "GET", "Users/" + encodePath(account.nativeId()), null);
            if (c.error() == null) {
                return new Lookup(c.json(), null);
            }
            if (!"ACCOUNT_NOT_FOUND".equals(c.error().code())) {
                return new Lookup(null, c.error());
            }
        }
        String filter = "userName eq \"" + account.name().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        Call c = call(ctx, "GET", "Users?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8), null);
        if (c.error() != null) {
            return new Lookup(null, c.error());
        }
        if (c.json().get("Resources") instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> user) {
            return new Lookup(user, null);
        }
        return new Lookup(null, new ProviderError("ACCOUNT_NOT_FOUND", "no unique SCIM user named " + account.name(), false, false));
    }

    AccountState state(Map<?, ?> user) {
        String id = String.valueOf(user.get("id"));
        String userName = user.get("userName") == null ? id : String.valueOf(user.get("userName"));
        boolean active = !Boolean.FALSE.equals(user.get("active"));
        List<GroupRef> groups = new ArrayList<>();
        boolean privileged = false;
        String privilegedVia = null;
        if (user.get("groups") instanceof List<?> gl) {
            for (Object g : gl) {
                if (g instanceof Map<?, ?> gm) {
                    String display = gm.get("display") == null ? String.valueOf(gm.get("value")) : String.valueOf(gm.get("display"));
                    groups.add(new GroupRef(gm.get("value") == null ? null : String.valueOf(gm.get("value")), display));
                    if (privilegedGroups.contains(display.toLowerCase(Locale.ROOT))) {
                        privileged = true;
                        privilegedVia = display;
                    }
                }
            }
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (user.get("displayName") != null) {
            attributes.put("displayName", String.valueOf(user.get("displayName")));
        }
        if (user.get("emails") instanceof List<?> emails && !emails.isEmpty() && emails.get(0) instanceof Map<?, ?> e && e.get("value") != null) {
            attributes.put("email", String.valueOf(e.get("value")));
        }
        if (privileged) {
            attributes.put("privilegeReason", "member of " + privilegedVia);
        }
        return new AccountState(new AccountRef(id, userName), active ? NativeAccountStatus.ENABLED : NativeAccountStatus.DISABLED,
                privileged, groups, null, null, null, attributes);
    }

    private record Call(Map<String, Object> json, ProviderError error) {
    }

    private Call call(OperationContext ctx, String method, String relative, String body) {
        String auth;
        try {
            auth = authorization(ctx);
        } catch (CredentialResolver.SecretsUnavailableException e) {
            return new Call(null, new ProviderError(OperationResult.SECRETS_UNAVAILABLE, e.getMessage(), true, false));
        }
        boolean mutating = !"GET".equals(method);
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(relative)).timeout(timeout)
                .header("Accept", "application/scim+json, application/json").header("Authorization", auth);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            b.header("Content-Type", "application/scim+json");
        }
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            return new Call(null, new ProviderError("OPERATION_TIMEOUT", "SCIM endpoint did not answer in " + timeout.toSeconds() + " s",
                    true, mutating));
        } catch (IOException e) {
            return new Call(null, new ProviderError("CONNECTION_FAILED", "SCIM endpoint unreachable: " + e.getClass().getSimpleName(),
                    true, false));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Call(null, new ProviderError("OPERATION_TIMEOUT", "interrupted", true, mutating));
        }
        int s = r.statusCode();
        if (s / 100 == 2) {
            return new Call(r.body() == null || r.body().isBlank() ? Map.of() : Json.parseObject(r.body()), null);
        }
        return new Call(null, switch (s) {
            case 401, 403 -> new ProviderError("AUTHENTICATION_FAILED", "SCIM endpoint rejected the credential (HTTP " + s + ")", false, false);
            case 404 -> new ProviderError("ACCOUNT_NOT_FOUND", "not found (HTTP 404)", false, false);
            case 429, 503 -> new ProviderError("PROVIDER_UNAVAILABLE", "SCIM endpoint busy or unavailable (HTTP " + s + ")", true, false);
            default -> new ProviderError(s >= 500 ? "PROVIDER_ERROR" : "PROVIDER_REJECTED", "SCIM endpoint answered HTTP " + s,
                    s >= 500, mutating && s >= 500);
        });
    }

    private String authorization(OperationContext ctx) throws CredentialResolver.SecretsUnavailableException {
        if (connection.credential() == null) {
            throw new CredentialResolver.SecretsUnavailableException("no credential configured for this provider instance");
        }
        try (Secret secret = ctx.credentials().redeem(connection.credential())) {
            char[] value = secret.reveal(); // provider credential use: sent only in the TLS request header
            try {
                if ("basic".equalsIgnoreCase(connection.settings().getOrDefault("authType", "bearer"))) {
                    String user = connection.settings().getOrDefault("username", "");
                    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + new String(value)).getBytes(StandardCharsets.UTF_8));
                }
                return "Bearer " + new String(value);
            } finally {
                Arrays.fill(value, '\0');
            }
        }
    }

    private int intSetting(String key, int def) {
        try {
            return Integer.parseInt(connection.settings().getOrDefault(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
