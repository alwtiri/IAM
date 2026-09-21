package com.enterprise.iam.core.audit.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditEventView;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.audit.api.AuditSearch;
import com.enterprise.iam.core.audit.api.AuditVerificationResult;
import com.enterprise.iam.core.audit.domain.AuditEvent;
import com.enterprise.iam.core.audit.domain.ChainHead;
import com.enterprise.iam.core.audit.domain.ChainVerifier;
import com.enterprise.iam.core.audit.domain.HashChain;
import com.enterprise.iam.core.shared.api.context.RequestContext;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;

/**
 * Audit recording and querying. Recording joins the caller's transaction (the store locks the chain head FOR UPDATE,
 * serializing appends); reading audit data is itself audited.
 */
public class AuditService implements AuditRecorder {

    public static final String PARTITION = "global";

    private final AuditStore store;
    private final RequestContextProvider context;
    private final AccessGuard guard;
    private final TransactionRunner tx;
    private final Clock clock;

    public AuditService(AuditStore store, RequestContextProvider context, AccessGuard guard, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.context = context;
        this.guard = guard;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void record(CurrentActor actor, AuditEntry entry) {
        RequestContext ctx = context.current();
        String actorType = actor == null ? "ANONYMOUS"
                : switch (actor.channel()) {
                    case SYSTEM -> "SYSTEM";
                    case BEARER_TOKEN -> "SERVICE";
                    case BROWSER_SESSION -> "USER";
                };
        AuditEvent draft = new AuditEvent(Ids.newId(clock), PARTITION, 0, clock.instant(),
                actor == null ? null : actor.identityId(), actorType, entry.action(), entry.objectType(), entry.objectId(),
                entry.targetId(), actor == null ? null : actor.channel().name(), entry.result().name(), entry.reason(),
                ctx.correlationId(), actor != null && actor.sourceIp() != null ? actor.sourceIp() : ctx.sourceIp(),
                entry.providerInstanceId(), entry.details(), null, null);
        ChainHead head = store.lockHead(PARTITION);
        AuditEvent chained = HashChain.append(head, draft);
        store.insert(chained);
        store.updateHead(new ChainHead(PARTITION, chained.seq(), chained.hash()));
    }

    /** Audit search is restricted to GLOBAL-scoped holders of audit:read (audit data spans all org units). */
    public PageResult<AuditEventView> search(CurrentActor actor, AuditSearch filter, PageRequest page) {
        guard.require(actor, Permissions.AUDIT_READ, ResourceScope.PLATFORM, false);
        return tx.inTransaction(() -> {
            PageResult<AuditEvent> result = PageResult.fromOverfetch(store.search(filter, page), page.limit(), AuditEvent::id);
            record(actor, AuditEntry.success("audit.searched", "audit", null, java.util.Map.of(
                    "returned", String.valueOf(result.items().size()))));
            return result.map(AuditService::view);
        });
    }

    public AuditVerificationResult verify(CurrentActor actor) {
        guard.require(actor, Permissions.AUDIT_VERIFY, ResourceScope.PLATFORM, false);
        AuditVerificationResult result = tx.readOnly(() -> {
            ChainVerifier verifier = new ChainVerifier();
            store.streamPartition(PARTITION, verifier::accept);
            verifier.acceptHead(store.readHead(PARTITION));
            return new AuditVerificationResult(PARTITION, verifier.valid(), verifier.checked(), verifier.firstBrokenSeq(),
                    verifier.problem(), clock.instant());
        });
        tx.run(() -> record(actor, new AuditEntry("audit.verified", "audit-chain", PARTITION, null,
                result.valid() ? AuditEntry.Result.SUCCESS : AuditEntry.Result.FAILURE,
                result.problem(), null, java.util.Map.of("eventsChecked", String.valueOf(result.eventsChecked())))));
        return result;
    }

    static AuditEventView view(AuditEvent e) {
        return new AuditEventView(e.id(), e.seq(), e.occurredAt(), e.actorIdentityId(), e.actorType(), e.action(),
                e.objectType(), e.objectId(), e.targetId(), e.source(), e.result(), e.reason(), e.correlationId(), e.ip(),
                e.providerInstanceId(), e.details(), HashChain.hex(e.hash()));
    }
}
