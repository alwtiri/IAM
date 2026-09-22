package com.enterprise.iam.core.secrets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.secrets.application.CredentialHandleService;
import com.enterprise.iam.core.secrets.application.CredentialHandleStore;
import com.enterprise.iam.core.shared.api.security.WorkerPrincipal;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Secret;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Handles: single use, TTL, bound to the worker's provider types, hashed at rest, Vault outage fails closed (G3). */
class CredentialHandleServiceTest {

    static final class MemoryHandles implements CredentialHandleStore {
        final Map<ByteBuffer, Row> rows = new HashMap<>();

        @Override
        public void insert(byte[] h, UUID op, String purpose, String ref, String ptype, Instant issuedAt, Instant expiresAt) {
            rows.put(ByteBuffer.wrap(h), new Row(op, purpose, ref, ptype, expiresAt, null));
        }

        @Override
        public Optional<Row> lock(byte[] h) {
            return Optional.ofNullable(rows.get(ByteBuffer.wrap(h)));
        }

        @Override
        public void markRedeemed(byte[] h, String by, Instant at) {
            Row r = rows.get(ByteBuffer.wrap(h));
            rows.put(ByteBuffer.wrap(h), new Row(r.operationId(), r.purpose(), r.secretRef(), r.providerType(), r.expiresAt(), at));
        }
    }

    static final class FakeVault implements SecretStore {
        boolean down;

        @Override
        public SecretRef write(String logicalPath, Secret value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Secret read(SecretRef ref) {
            if (down) {
                throw IamException.secretsUnavailable(null);
            }
            return Secret.of("s3cr3t-for-" + ref.path());
        }

        @Override
        public void destroy(SecretRef ref) {
        }
    }

    static final WorkerPrincipal LINUX_WORKER = new WorkerPrincipal("iam-worker-os", Set.of("linux-ssh"));
    static final WorkerPrincipal AD_WORKER = new WorkerPrincipal("iam-worker-directory", Set.of("active-directory"));
    static final SecretRef REF = SecretRef.of("iam", "providers/p1/connection", 3);

    MemoryHandles store;
    FakeVault vault;
    List<AuditEntry> audited;
    TestSupport.MutableClock clock;
    CredentialHandleService service;

    @BeforeEach
    void setUp() {
        store = new MemoryHandles();
        vault = new FakeVault();
        audited = new ArrayList<>();
        clock = new TestSupport.MutableClock(Instant.parse("2026-09-22T10:00:00Z"));
        service = new CredentialHandleService(store, vault, (a, e) -> audited.add(e), TestSupport.DIRECT_TX, clock);
    }

    String issue() {
        return service.issue(UUID.randomUUID(), "linux-ssh", Map.of("connection", REF), Duration.ofSeconds(60)).get("connection");
    }

    @Test
    void handleIsOpaqueAndOnlyItsHashIsStored() {
        String h = issue();
        assertTrue(h.startsWith("ch_") && h.length() > 40);
        assertEquals(1, store.rows.size());
        assertFalse(store.rows.values().iterator().next().toString().contains(h));
        assertTrue(audited.stream().anyMatch(e -> e.action().equals("credential.handles-issued")));
    }

    @Test
    void redeemReturnsTheSecretOnce() {
        String h = issue();
        try (Secret s = service.redeem(h, LINUX_WORKER)) {
            assertArrayEquals("s3cr3t-for-providers/p1/connection".toCharArray(), s.reveal());
        }
        assertThrows(IamException.class, () -> service.redeem(h, LINUX_WORKER));
        assertTrue(audited.stream().anyMatch(e -> e.action().equals("credential.redeem") && e.result() == AuditEntry.Result.DENIED
                && "already redeemed".equals(e.reason())));
    }

    @Test
    void expiredForeignAndUnknownHandlesAreNotFound() {
        String h = issue();
        IamException foreign = assertThrows(IamException.class, () -> service.redeem(h, AD_WORKER));
        assertEquals("NOT_FOUND", foreign.code().name());
        clock.advanceSeconds(61);
        assertThrows(IamException.class, () -> service.redeem(h, LINUX_WORKER));
        assertThrows(IamException.class, () -> service.redeem("ch_doesnotexist", LINUX_WORKER));
        assertThrows(IamException.class, () -> service.redeem("not-a-handle", LINUX_WORKER));
    }

    @Test
    void vaultOutageFailsClosedAndLeavesTheHandleRedeemable() {
        String h = issue();
        vault.down = true;
        IamException e = assertThrows(IamException.class, () -> service.redeem(h, LINUX_WORKER));
        assertEquals("SECRETS_UNAVAILABLE", e.code().name());
        assertTrue(store.rows.values().iterator().next().redeemedAt() == null, "not consumed by a failed redemption");
        vault.down = false;
        service.redeem(h, LINUX_WORKER).close();
    }

    @Test
    void ttlIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> service.issue(UUID.randomUUID(), "linux-ssh", Map.of("connection", REF),
                Duration.ofMinutes(16)));
    }
}
