package com.enterprise.iam.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.domain.AuditEvent;
import com.enterprise.iam.core.audit.domain.ChainHead;
import com.enterprise.iam.core.audit.domain.ChainVerifier;
import com.enterprise.iam.core.audit.domain.HashChain;
import com.enterprise.iam.kernel.Ids;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HashChainTest {

    private static AuditEvent draft(String action, Map<String, String> details) {
        return new AuditEvent(Ids.newId(), "global", 0, Instant.parse("2026-09-21T10:00:00.123456789Z"), UUID.randomUUID(),
                "USER", action, "identity", "id-1", null, "BROWSER_SESSION", "SUCCESS", null, "corr-12345", "10.0.0.1",
                null, details, null, null);
    }

    private static List<AuditEvent> chain(int n) {
        List<AuditEvent> events = new ArrayList<>();
        ChainHead head = ChainHead.genesis("global");
        for (int i = 0; i < n; i++) {
            AuditEvent e = HashChain.append(head, draft("identity.updated", Map.of("field", "f" + i)));
            events.add(e);
            head = new ChainHead("global", e.seq(), e.hash());
        }
        return events;
    }

    private static ChainVerifier verify(List<AuditEvent> events, ChainHead head) {
        ChainVerifier v = new ChainVerifier();
        events.forEach(v::accept);
        v.acceptHead(head);
        return v;
    }

    @Test
    void intactChainVerifies() {
        List<AuditEvent> events = chain(5);
        AuditEvent last = events.get(4);
        ChainVerifier v = verify(events, new ChainHead("global", 5, last.hash()));
        assertTrue(v.valid(), v.problem());
        assertEquals(5, v.checked());
        assertEquals(5, last.seq());
    }

    @Test
    void timestampIsTruncatedToDatabasePrecision() {
        assertEquals(Instant.parse("2026-09-21T10:00:00.123456Z"), draft("a.b", Map.of()).occurredAt());
    }

    @Test
    void modifiedContentIsDetected() {
        List<AuditEvent> events = chain(4);
        AuditEvent e = events.get(2);
        AuditEvent tampered = new AuditEvent(e.id(), e.chainPartition(), e.seq(), e.occurredAt(), e.actorIdentityId(),
                e.actorType(), e.action(), e.objectType(), e.objectId(), e.targetId(), e.source(), "FAILURE", e.reason(),
                e.correlationId(), e.ip(), e.providerInstanceId(), e.details(), e.prevHash(), e.hash());
        events.set(2, tampered);
        ChainVerifier v = verify(events, new ChainHead("global", 4, events.get(3).hash()));
        assertFalse(v.valid());
        assertEquals(3L, v.firstBrokenSeq());
    }

    @Test
    void deletedEventIsDetected() {
        List<AuditEvent> events = chain(4);
        events.remove(1);
        ChainVerifier v = verify(events, new ChainHead("global", 4, events.get(2).hash()));
        assertFalse(v.valid());
        assertEquals(3L, v.firstBrokenSeq());
    }

    @Test
    void truncatedTailIsDetectedAgainstHead() {
        List<AuditEvent> events = chain(4);
        AuditEvent last = events.remove(3);
        ChainVerifier v = verify(events, new ChainHead("global", 4, last.hash()));
        assertFalse(v.valid());
    }

    @Test
    void canonicalFormIsIndependentOfDetailOrder() {
        AuditEvent a = draft("x.y", Map.of("a", "1", "b", "2"));
        java.util.LinkedHashMap<String, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put("b", "2");
        reversed.put("a", "1");
        AuditEvent b = new AuditEvent(a.id(), "global", 0, a.occurredAt(), a.actorIdentityId(), "USER", "x.y", "identity",
                "id-1", null, "BROWSER_SESSION", "SUCCESS", null, "corr-12345", "10.0.0.1", null, reversed, null, null);
        assertEquals(HashChain.canonical(a, 1), HashChain.canonical(b, 1));
    }
}
