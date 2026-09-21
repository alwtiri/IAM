package com.enterprise.iam.core.audit.domain;

import com.enterprise.iam.kernel.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Tamper-evident chaining (ADR-0008): {@code hash = SHA-256(prevHash || UTF-8(canonical(event)))}.
 * The canonical form is a JSON array with a fixed field order (version tag first) and sorted detail keys, so it is
 * independent of storage order and JSON library behaviour.
 */
public final class HashChain {

    public static final String CANONICAL_VERSION = "iam-audit-v1";
    public static final byte[] GENESIS = new byte[32];

    private HashChain() {
    }

    public static String canonical(AuditEvent e, long seq) {
        List<Object> fields = new ArrayList<>();
        fields.add(CANONICAL_VERSION);
        fields.add(e.chainPartition());
        fields.add(seq);
        fields.add(e.id().toString());
        fields.add(DateTimeFormatter.ISO_INSTANT.format(e.occurredAt()));
        fields.add(str(e.actorIdentityId()));
        fields.add(e.actorType());
        fields.add(e.action());
        fields.add(e.objectType());
        fields.add(e.objectId());
        fields.add(str(e.targetId()));
        fields.add(e.source());
        fields.add(e.result());
        fields.add(e.reason());
        fields.add(e.correlationId());
        fields.add(e.ip());
        fields.add(str(e.providerInstanceId()));
        fields.add(e.details());
        return Json.writeCanonical(fields);
    }

    public static byte[] hash(byte[] prevHash, AuditEvent e, long seq) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prevHash);
            md.update(canonical(e, seq).getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Assigns the next chain position to {@code event} given the current head. */
    public static AuditEvent append(ChainHead head, AuditEvent event) {
        long seq = head.lastSeq() + 1;
        byte[] h = hash(head.lastHash(), event, seq);
        return event.withChain(seq, head.lastHash(), h);
    }

    public static String hex(byte[] bytes) {
        return bytes == null ? null : HexFormat.of().formatHex(bytes);
    }

    public static boolean same(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
