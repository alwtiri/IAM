package com.enterprise.iam.providers.genericrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protocol-level behaviour against an in-process SCIM 2.0 service provider. */
class ScimProviderTest {

    static OperationContext ctx(String secret) {
        return new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1, Instant.now().plusSeconds(30),
                handle -> Secret.of(secret));
    }

    static Provider provider(FakeScimServer s, Map<String, String> settings) {
        Map<String, String> all = new java.util.HashMap<>(settings);
        all.put("allowInsecureHttp", "true");
        return new ScimProviderFactory().create(new ProviderConnection(UUID.randomUUID(), ScimProvider.TYPE, s.endpoint(), all,
                new CredentialHandle("ch_test")));
    }

    @Test
    void discoveryPagesThroughAllUsersAndDetectsPrivilege() throws Exception {
        try (FakeScimServer s = new FakeScimServer()) {
            for (int i = 0; i < 5; i++) {
                s.addUser("id" + i, "user" + i, i != 3, i == 1 ? "Admins" : "Staff");
            }
            Provider p = provider(s, Map.of("pageSize", "2", "privilegedGroups", "admins, root"));
            List<AccountState> all = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                OperationResult<Page<AccountState>> r = p.discoverAccounts(ctx("test-token"), cursor);
                assertTrue(r.isSuccess(), r.toString());
                all.addAll(r.value().orElseThrow().items());
                cursor = r.value().orElseThrow().nextCursor();
                pages++;
            } while (cursor != null);
            assertEquals(3, pages);
            assertEquals(5, all.size());
            AccountState admin = all.get(1);
            assertTrue(admin.privileged());
            assertEquals("member of Admins", admin.attributes().get("privilegeReason"));
            assertEquals(NativeAccountStatus.DISABLED, all.get(3).status());
            assertEquals("USER0", all.get(0).attributes().get("displayName"));
        }
    }

    @Test
    void disableIsVerifiedByReadBackAndIdempotent() throws Exception {
        try (FakeScimServer s = new FakeScimServer()) {
            s.addUser("u-1", "alice", true);
            Provider p = provider(s, Map.of());
            OperationResult<AccountState> r = p.disableAccount(ctx("test-token"), new AccountRef(null, "alice"));
            assertEquals(OperationOutcome.SUCCEEDED, r.outcome());
            assertTrue(r.verification().isPresent());
            assertEquals(NativeAccountStatus.DISABLED, r.value().orElseThrow().status());
            assertEquals(1, s.patches.get());
            OperationResult<AccountState> again = p.disableAccount(ctx("test-token"), new AccountRef("u-1", "alice"));
            assertEquals(OperationOutcome.SUCCEEDED, again.outcome());
            assertEquals(1, s.patches.get(), "already disabled: no second change");
        }
    }

    @Test
    void changeThatDoesNotStickIsUnknownNotSuccess() throws Exception {
        try (FakeScimServer s = new FakeScimServer()) {
            s.addUser("u-1", "alice", true);
            s.ignorePatch = true;
            OperationResult<AccountState> r = provider(s, Map.of()).disableAccount(ctx("test-token"), new AccountRef(null, "alice"));
            assertEquals(OperationOutcome.UNKNOWN, r.outcome());
            assertNull(r.verification().orElse(null));
        }
    }

    @Test
    void errorsAreClassified() throws Exception {
        try (FakeScimServer s = new FakeScimServer()) {
            s.addUser("u-1", "alice", true);
            Provider p = provider(s, Map.of());
            var badToken = p.getAccountState(ctx("wrong"), new AccountRef(null, "alice"));
            assertEquals("AUTHENTICATION_FAILED", badToken.error().orElseThrow().code());
            assertFalse(badToken.error().orElseThrow().retryable());
            var missing = p.getAccountState(ctx("test-token"), new AccountRef(null, "nobody"));
            assertEquals("ACCOUNT_NOT_FOUND", missing.error().orElseThrow().code());
            s.forceStatus = 503;
            var busy = p.getAccountState(ctx("test-token"), new AccountRef(null, "alice"));
            assertEquals("PROVIDER_UNAVAILABLE", busy.error().orElseThrow().code());
            assertTrue(busy.error().orElseThrow().retryable());
        }
        Provider unreachable = new ScimProviderFactory().create(new ProviderConnection(UUID.randomUUID(), ScimProvider.TYPE,
                "http://127.0.0.1:9/scim/v2", Map.of("allowInsecureHttp", "true"), new CredentialHandle("ch_x")));
        assertEquals("CONNECTION_FAILED", unreachable.validateConnection(ctx("t")).error().orElseThrow().code());
    }

    @Test
    void basicAuthAndVaultOutage() throws Exception {
        try (FakeScimServer s = new FakeScimServer()) {
            s.expectedAuth = "Basic " + java.util.Base64.getEncoder().encodeToString("svc-iam:pw".getBytes());
            Provider p = provider(s, Map.of("authType", "basic", "username", "svc-iam"));
            assertTrue(p.validateConnection(ctx("pw")).isSuccess());
            OperationContext noVault = new OperationContext(UUID.randomUUID(), "key-12345678", "corr-12345678", 1,
                    Instant.now().plusSeconds(30), handle -> {
                        throw new com.enterprise.iam.provider.spi.CredentialResolver.SecretsUnavailableException("vault down");
                    });
            assertEquals("SECRETS_UNAVAILABLE", p.validateConnection(noVault).error().orElseThrow().code());
        }
    }

    @Test
    void plainHttpIsRefusedUnlessExplicitlyAllowed() {
        assertThrows(IllegalArgumentException.class, () -> new ScimProviderFactory().create(new ProviderConnection(UUID.randomUUID(),
                ScimProvider.TYPE, "http://app/scim/v2", Map.of(), new CredentialHandle("ch_x"))));
    }
}
