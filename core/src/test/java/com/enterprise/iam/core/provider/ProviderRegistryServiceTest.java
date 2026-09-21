package com.enterprise.iam.core.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.provider.api.ProviderInstanceView;
import com.enterprise.iam.core.provider.application.ProviderRegistryService;
import com.enterprise.iam.core.provider.application.ProviderStore;
import com.enterprise.iam.core.provider.domain.ProviderInstance;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Secret;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** G3: credentials only in Vault; Vault down → nothing persisted; DB failure → Vault entry removed. */
class ProviderRegistryServiceTest {

    static final class MemoryProviders implements ProviderStore {
        final Map<UUID, ProviderInstance> rows = new LinkedHashMap<>();
        boolean failInsert;

        @Override
        public void insert(ProviderInstance p) {
            if (failInsert) {
                throw new IllegalStateException("database unavailable");
            }
            rows.put(p.id(), p);
        }

        @Override
        public boolean setEnabled(UUID id, boolean enabled, long v) {
            rows.put(id, rows.get(id).withEnabled(enabled));
            return true;
        }

        @Override
        public Optional<ProviderInstance> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<ProviderInstanceView> view(UUID id) {
            return find(id).map(p -> new ProviderInstanceView(p.id(), p.type().value(), p.name(), p.endpoint(), p.settings(),
                    p.credentialSecretRef() != null, p.enabled(), "UNKNOWN", "CLOSED", null, null, p.version()));
        }

        @Override
        public boolean nameExists(String name) {
            return rows.values().stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
        }

        @Override
        public List<ProviderInstanceView> list(ScopeFilter filter, String type, PageRequest page) {
            return rows.keySet().stream().map(id -> view(id).orElseThrow()).toList();
        }
    }

    static final class FakeVault implements SecretStore {
        boolean down;
        final List<String> written = new ArrayList<>();
        final List<String> destroyed = new ArrayList<>();

        @Override
        public SecretRef write(String path, Secret value) {
            if (down) {
                throw IamException.secretsUnavailable(null);
            }
            written.add(path);
            return SecretRef.of("iam", path, 1);
        }

        @Override
        public void destroy(SecretRef ref) {
            destroyed.add(ref.path());
        }
    }

    private final MemoryProviders store = new MemoryProviders();
    private final FakeVault vault = new FakeVault();
    private final ProviderRegistryService service = new ProviderRegistryService(store, vault, TestSupport.guard(true), (a, e) -> { },
            TestSupport.DIRECT_TX, Clock.systemUTC());

    private ProviderRegistryService.RegisterCommand cmd(String name, Map<String, String> settings) {
        return new ProviderRegistryService.RegisterCommand("linux", name, "ssh://srv01.example.org:22", settings, Secret.of("pw"));
    }

    @Test
    void registrationStoresOnlyAReference() {
        ProviderInstanceView v = service.register(TestSupport.actor(UUID.randomUUID()), cmd("linux-prod", Map.of("sudo", "true")));
        assertTrue(v.credentialConfigured());
        assertTrue(store.rows.get(v.id()).credentialSecretRef().startsWith("vault:iam/providers/"));
    }

    @Test
    void vaultOutageFailsClosedAndPersistsNothing() {
        vault.down = true;
        IamException e = assertThrows(IamException.class, () -> service.register(TestSupport.actor(UUID.randomUUID()), cmd("x", Map.of())));
        assertEquals(ErrorCode.SECRETS_UNAVAILABLE, e.code());
        assertTrue(store.rows.isEmpty());
    }

    @Test
    void databaseFailureRemovesTheVaultEntry() {
        store.failInsert = true;
        assertThrows(IllegalStateException.class, () -> service.register(TestSupport.actor(UUID.randomUUID()), cmd("y", Map.of())));
        assertEquals(vault.written, vault.destroyed);
    }

    @Test
    void secretLikeSettingsAreRejectedBeforeVaultIsTouched() {
        IamException e = assertThrows(IamException.class,
                () -> service.register(TestSupport.actor(UUID.randomUUID()), cmd("z", Map.of("adminPassword", "p"))));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        assertTrue(vault.written.isEmpty());
        assertFalse(service.catalog(TestSupport.actor(UUID.randomUUID())).capabilities().isEmpty());
    }
}
