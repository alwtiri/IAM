package com.enterprise.iam.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditSearch;
import com.enterprise.iam.core.audit.api.AuditVerificationResult;
import com.enterprise.iam.core.audit.application.AuditService;
import com.enterprise.iam.core.audit.application.AuditStore;
import com.enterprise.iam.core.audit.domain.AuditEvent;
import com.enterprise.iam.core.audit.domain.ChainHead;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.testsupport.TestSupport;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.IamException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AuditServiceTest {

    /** In-memory store with the same semantics as the JDBC adapter. */
    static final class MemoryStore implements AuditStore {
        final List<AuditEvent> events = new ArrayList<>();
        final Map<String, ChainHead> heads = new HashMap<>();

        @Override
        public ChainHead lockHead(String partition) {
            return heads.computeIfAbsent(partition, ChainHead::genesis);
        }

        @Override
        public void insert(AuditEvent event) {
            events.add(event);
        }

        @Override
        public void updateHead(ChainHead head) {
            heads.put(head.chainPartition(), head);
        }

        @Override
        public List<AuditEvent> search(AuditSearch filter, PageRequest page) {
            return events.stream().sorted(Comparator.comparing(AuditEvent::id).reversed()).limit(page.limit() + 1L).toList();
        }

        @Override
        public void streamPartition(String partition, Consumer<AuditEvent> consumer) {
            events.stream().sorted(Comparator.comparingLong(AuditEvent::seq)).forEach(consumer);
        }

        @Override
        public ChainHead readHead(String partition) {
            return heads.getOrDefault(partition, ChainHead.genesis(partition));
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final AuditService service = new AuditService(store, TestSupport.CONTEXT, TestSupport.guard(true),
            TestSupport.DIRECT_TX, Clock.systemUTC());

    @Test
    void recordsChainedEventsWithContext() {
        UUID actorId = UUID.randomUUID();
        service.record(TestSupport.actor(actorId), AuditEntry.success("identity.created", "identity", "i1", Map.of("type", "EMPLOYEE")));
        service.record(null, AuditEntry.denied("auth.login", "session", null, "unknown subject"));
        assertEquals(2, store.events.size());
        AuditEvent first = store.events.get(0);
        assertEquals(1, first.seq());
        assertEquals("USER", first.actorType());
        assertEquals("test-correlation-1", first.correlationId());
        assertEquals("ANONYMOUS", store.events.get(1).actorType());
        assertEquals(2, store.readHead("global").lastSeq());
    }

    @Test
    void verificationPassesAndIsItselfAudited() {
        service.record(TestSupport.actor(UUID.randomUUID()), AuditEntry.success("a.b", "x", "1", Map.of()));
        AuditVerificationResult r = service.verify(TestSupport.actor(UUID.randomUUID()));
        assertTrue(r.valid(), r.problem());
        assertEquals(1, r.eventsChecked());
        assertEquals("audit.verified", store.events.get(1).action());
    }

    @Test
    void secretLikeDetailKeysAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditEntry.success("provider.created", "provider", "p", Map.of("adminPassword", "x")));
        assertThrows(IllegalArgumentException.class, () -> AuditEntry.success("Bad Action", "x", "1", Map.of()));
    }

    @Test
    void searchRequiresGlobalAuditPermission() {
        AuditService denied = new AuditService(store, TestSupport.CONTEXT, TestSupport.guard(false), TestSupport.DIRECT_TX, Clock.systemUTC());
        IamException e = assertThrows(IamException.class,
                () -> denied.search(TestSupport.actor(UUID.randomUUID()), new AuditSearch(null, null, null, null, null, null, null),
                        PageRequest.of(10, null)));
        assertEquals(ErrorCode.ACCESS_DENIED, e.code());
    }
}
