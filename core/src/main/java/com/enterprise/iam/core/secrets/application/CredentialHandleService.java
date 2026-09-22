package com.enterprise.iam.core.secrets.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.secrets.api.CredentialHandles;
import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.security.WorkerPrincipal;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Secret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and redeems credential handles. A handle is 256 bits of randomness; only its SHA-256 is stored. Redemption is
 * single-use, time-boxed, bound to the provider type the worker serves, audited, and fails closed. The secret value is
 * read from Vault at redemption time and returned to the worker only (never cached, never logged).
 */
public class CredentialHandleService implements CredentialHandles {

    public static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final Duration MAX_TTL = Duration.ofMinutes(15);
    private static final String PREFIX = "ch_";
    private static final CurrentActor SYSTEM = CurrentActor.system(SystemIdentities.SYSTEM_IDENTITY_ID);

    private final CredentialHandleStore store;
    private final SecretStore secrets;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public CredentialHandleService(CredentialHandleStore store, SecretStore secrets, AuditRecorder audit, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.secrets = secrets;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Map<String, String> issue(UUID operationId, String providerType, Map<String, SecretRef> purposes, Duration ttl) {
        Duration life = ttl == null ? DEFAULT_TTL : ttl;
        if (life.isNegative() || life.isZero() || life.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("handle TTL must be within (0, 15 min]");
        }
        return tx.inTransaction(() -> {
            Instant now = clock.instant();
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : purposes.entrySet()) {
                if (!e.getKey().matches("^[a-z][a-z-]{1,31}$")) {
                    throw new IllegalArgumentException("invalid handle purpose");
                }
                byte[] raw = new byte[32];
                random.nextBytes(raw);
                String handle = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
                store.insert(hash(handle), operationId, e.getKey(), e.getValue().value(), providerType, now, now.plus(life));
                out.put(e.getKey(), handle);
            }
            audit.record(SYSTEM, new AuditEntry("credential.handles-issued", "operation", operationId.toString(), null,
                    AuditEntry.Result.SUCCESS, null, null, Map.of("purposes", String.join(",", purposes.keySet()), "providerType", providerType)));
            return Map.copyOf(out);
        });
    }

    /**
     * Redeems a handle for the calling worker.
     *
     * @throws IamException NOT_FOUND for unknown, expired, redeemed, or foreign handles (no oracle about which);
     *                      SECRETS_UNAVAILABLE when Vault is down (the handle stays redeemable until it expires)
     */
    public Secret redeem(String handle, WorkerPrincipal worker) {
        if (handle == null || !handle.startsWith(PREFIX) || handle.length() > 128) {
            throw IamException.notFound("Credential handle");
        }
        byte[] h = hash(handle);
        String denial = tx.inTransaction(() -> {
            var row = store.lock(h).orElse(null);
            Instant now = clock.instant();
            String reason = row == null ? "unknown handle"
                    : row.redeemedAt() != null ? "already redeemed"
                    : !row.expiresAt().isAfter(now) ? "expired"
                    : !worker.serves(row.providerType()) ? "provider type " + row.providerType() + " not served by " + worker.subject()
                    : null;
            if (reason != null) {
                audit.record(SYSTEM, new AuditEntry("credential.redeem", "operation", row == null ? null : row.operationId().toString(), null,
                        AuditEntry.Result.DENIED, reason, null, Map.of("worker", worker.subject())));
            }
            return reason;
        });
        if (denial != null) {
            throw IamException.notFound("Credential handle");
        }
        // Read from Vault and mark redeemed in one transaction: if Vault is down, nothing is marked and the worker may retry.
        return tx.inTransaction(() -> {
            var row = store.lock(h).orElseThrow(() -> IamException.notFound("Credential handle"));
            if (row.redeemedAt() != null) {
                throw IamException.notFound("Credential handle"); // lost a race with a concurrent redemption
            }
            Secret value = secrets.read(new SecretRef(row.secretRef()));
            Instant now = clock.instant();
            store.markRedeemed(h, worker.subject(), now);
            audit.record(SYSTEM, new AuditEntry("credential.redeem", "operation", row.operationId().toString(), null,
                    AuditEntry.Result.SUCCESS, null, null, Map.of("worker", worker.subject(), "purpose", row.purpose())));
            return value;
        });
    }

    static byte[] hash(String handle) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(handle.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
