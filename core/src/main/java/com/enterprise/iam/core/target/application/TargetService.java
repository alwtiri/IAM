package com.enterprise.iam.core.target.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.target.api.TargetDirectory;
import com.enterprise.iam.core.target.api.TargetView;
import com.enterprise.iam.core.target.domain.Target;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Target metadata (spec §10). Scope = owning org unit + environment + target id. */
public class TargetService implements TargetDirectory {

    public record TargetData(String name, String hostname, String ipAddress, String dnsName, Target.Type type, String platform,
                             String operatingSystem, String environment, Target.Criticality criticality,
                             Target.Classification classification, UUID ownerOrgUnitId, UUID ownerIdentityId,
                             UUID technicalOwnerIdentityId, UUID businessOwnerIdentityId, UUID locationId, List<String> tags,
                             Target.Status status) {
    }

    private final TargetStore store;
    private final OrganizationDirectory org;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;

    public TargetService(TargetStore store, OrganizationDirectory org, AccessGuard guard, AuditRecorder audit, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.org = org;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public TargetView create(CurrentActor actor, TargetData d) {
        return tx.inTransaction(() -> {
            Target t = toTarget(Ids.newId(clock), d, 0);
            String path = orgPath(t.ownerOrgUnitId());
            guard.require(actor, Permissions.TARGET_WRITE, ResourceScope.target(null, t.environment(), path), false);
            if (store.nameExists(t.name(), t.id())) {
                throw IamException.alreadyExists("Target " + t.name());
            }
            store.insert(t);
            audit.record(actor, new AuditEntry("target.created", "target", t.id().toString(), t.id(), AuditEntry.Result.SUCCESS, null, null,
                    Map.of("type", t.type().name(), "environment", t.environment(), "name", t.name())));
            return view(t);
        });
    }

    public TargetView update(CurrentActor actor, UUID id, TargetData d, long expectedVersion) {
        return tx.inTransaction(() -> {
            TargetStore.Scoped cur = store.find(id).orElseThrow(() -> IamException.notFound("Target"));
            guard.require(actor, Permissions.TARGET_WRITE, scope(cur), true);
            Target next = toTarget(id, d, expectedVersion);
            guard.require(actor, Permissions.TARGET_WRITE, ResourceScope.target(id, next.environment(), orgPath(next.ownerOrgUnitId())), false);
            if (store.nameExists(next.name(), id)) {
                throw IamException.alreadyExists("Target " + next.name());
            }
            if (!store.update(next, expectedVersion)) {
                throw IamException.concurrentModification("Target");
            }
            audit.record(actor, new AuditEntry("target.updated", "target", id.toString(), id, AuditEntry.Result.SUCCESS, null, null,
                    Map.of("environment", next.environment(), "status", next.status().name())));
            return view(toTarget(id, d, expectedVersion + 1));
        });
    }

    public TargetView get(CurrentActor actor, UUID id) {
        TargetStore.Scoped t = tx.readOnly(() -> store.find(id)).orElseThrow(() -> IamException.notFound("Target"));
        guard.require(actor, Permissions.TARGET_READ, scope(t), true);
        return view(t.target());
    }

    public PageResult<TargetView> list(CurrentActor actor, String type, String environment, PageRequest page) {
        var filter = guard.filter(actor, Permissions.TARGET_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.list(filter, type, environment, page), page.limit(), s -> s.target().id()))
                .map(s -> view(s.target()));
    }

    @Override
    public void decommission(CurrentActor actor, UUID id, String reason) {
        tx.run(() -> {
            TargetStore.Scoped cur = store.find(id).orElseThrow(() -> IamException.notFound("Target"));
            guard.require(actor, Permissions.TARGET_WRITE, scope(cur), true);
            Target t = cur.target();
            if (t.status() == Target.Status.DECOMMISSIONED) {
                return;
            }
            String suffix = " [deleted " + java.time.LocalDate.now(clock) + " " + id.toString().substring(0, 8) + "]";
            String base = t.name().length() + suffix.length() > 200 ? t.name().substring(0, 200 - suffix.length()) : t.name();
            Target next = new Target(id, base + suffix, t.hostname(), t.ipAddress(), t.dnsName(), t.type(), t.platform(), t.operatingSystem(),
                    t.environment(), t.criticality(), t.classification(), t.ownerOrgUnitId(), t.ownerIdentityId(), t.technicalOwnerIdentityId(),
                    t.businessOwnerIdentityId(), t.locationId(), t.tags(), Target.Status.DECOMMISSIONED, t.version());
            if (!store.update(next, t.version())) {
                throw IamException.concurrentModification("Target");
            }
            audit.record(actor, new AuditEntry("target.decommissioned", "target", id.toString(), id, AuditEntry.Result.SUCCESS, reason, null,
                    Map.of("name", t.name(), "type", t.type().name())));
        });
    }

    @Override
    public Optional<ResourceScope> scopeOf(UUID targetId) {
        return store.find(targetId).map(TargetService::scope);
    }

    private String orgPath(UUID orgUnitId) {
        return org.orgUnitPath(orgUnitId).orElseThrow(() -> IamException.validation("ownerOrgUnitId", "NOT_FOUND", "org unit does not exist"));
    }

    static ResourceScope scope(TargetStore.Scoped s) {
        return ResourceScope.target(s.target().id(), s.target().environment(), s.orgUnitPath());
    }

    private static Target toTarget(UUID id, TargetData d, long version) {
        return new Target(id, d.name(), d.hostname(), d.ipAddress(), d.dnsName(), d.type(), d.platform(), d.operatingSystem(),
                d.environment(), d.criticality(), d.classification(), d.ownerOrgUnitId(), d.ownerIdentityId(),
                d.technicalOwnerIdentityId(), d.businessOwnerIdentityId(), d.locationId(), d.tags(), d.status(), version);
    }

    static TargetView view(Target t) {
        return new TargetView(t.id(), t.name(), t.hostname(), t.ipAddress(), t.dnsName(), t.type().name(), t.platform(), t.operatingSystem(),
                t.environment(), t.criticality().name(), t.classification().name(), t.ownerOrgUnitId(), t.ownerIdentityId(),
                t.technicalOwnerIdentityId(), t.businessOwnerIdentityId(), t.locationId(), t.tags(), t.status().name(),
                "NOT_DISCOVERED", "NOT_RECONCILED", "UNKNOWN", t.version());
    }
}
