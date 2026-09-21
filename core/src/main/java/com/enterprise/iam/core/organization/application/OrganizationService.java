package com.enterprise.iam.core.organization.application;

import com.enterprise.iam.core.audit.api.AuditEntry;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.organization.api.CatalogEntryView;
import com.enterprise.iam.core.organization.api.OrgUnitView;
import com.enterprise.iam.core.organization.application.OrganizationStore.Catalog;
import com.enterprise.iam.core.organization.domain.CatalogEntry;
import com.enterprise.iam.core.organization.domain.OrgUnit;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.SystemIdentities;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Organization model use cases (spec §5, PHASE-2-DESIGN F1). */
public class OrganizationService {

    public record CreateOrgUnit(UUID parentId, OrgUnit.Kind kind, String code, String name) {
    }

    private final OrganizationStore store;
    private final AccessGuard guard;
    private final AuditRecorder audit;
    private final TransactionRunner tx;
    private final Clock clock;

    public OrganizationService(OrganizationStore store, AccessGuard guard, AuditRecorder audit, TransactionRunner tx, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    public OrgUnitView create(CurrentActor actor, CreateOrgUnit cmd) {
        return tx.inTransaction(() -> {
            UUID id = Ids.newId(clock);
            OrgUnit unit;
            if (cmd.parentId() == null) {
                guard.require(actor, Permissions.ORG_WRITE, ResourceScope.PLATFORM, false);
                unit = OrgUnit.createRoot(id, SystemIdentities.DEFAULT_ORGANIZATION_ID, cmd.kind(), cmd.code(), cmd.name());
            } else {
                OrgUnit parent = store.find(cmd.parentId()).orElseThrow(() -> IamException.notFound("Parent org unit"));
                guard.require(actor, Permissions.ORG_WRITE, ResourceScope.orgUnit(parent.path()), true);
                unit = OrgUnit.createChild(id, parent, cmd.kind(), cmd.code(), cmd.name());
            }
            if (store.codeExists(unit.organizationId(), unit.code())) {
                throw IamException.alreadyExists("Org unit code " + unit.code());
            }
            store.insert(unit);
            audit.record(actor, AuditEntry.success("org-unit.created", "org-unit", id,
                    Map.of("code", unit.code(), "kind", unit.kind().name(), "parentId", String.valueOf(unit.parentId()))));
            return view(unit);
        });
    }

    public OrgUnitView rename(CurrentActor actor, UUID id, String name, long expectedVersion) {
        return tx.inTransaction(() -> {
            OrgUnit unit = store.find(id).orElseThrow(() -> IamException.notFound("Org unit"));
            guard.require(actor, Permissions.ORG_WRITE, ResourceScope.orgUnit(unit.path()), true);
            OrgUnit renamed = unit.rename(name);
            if (!store.update(renamed, expectedVersion)) {
                throw IamException.concurrentModification("Org unit");
            }
            audit.record(actor, AuditEntry.success("org-unit.renamed", "org-unit", id, Map.of("name", renamed.name())));
            return view(new OrgUnit(renamed.id(), renamed.organizationId(), renamed.parentId(), renamed.kind(), renamed.code(),
                    renamed.name(), renamed.path(), expectedVersion + 1));
        });
    }

    public OrgUnitView get(CurrentActor actor, UUID id) {
        OrgUnit unit = tx.readOnly(() -> store.find(id)).orElseThrow(() -> IamException.notFound("Org unit"));
        guard.require(actor, Permissions.ORG_READ, ResourceScope.orgUnit(unit.path()), true);
        return view(unit);
    }

    public PageResult<OrgUnitView> list(CurrentActor actor, UUID parentId, PageRequest page) {
        var filter = guard.filter(actor, Permissions.ORG_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(store.list(filter, parentId, page), page.limit(), OrgUnit::id))
                .map(OrganizationService::view);
    }

    public CatalogEntryView addCatalogEntry(CurrentActor actor, Catalog catalog, String code, String name, String detail) {
        return tx.inTransaction(() -> {
            guard.require(actor, Permissions.ORG_WRITE, ResourceScope.PLATFORM, false);
            CatalogEntry entry = new CatalogEntry(Ids.newId(clock), code, name, detail);
            if (store.catalogCodeExists(catalog, code)) {
                throw IamException.alreadyExists(catalog.name().toLowerCase() + " code " + code);
            }
            store.insert(catalog, entry);
            audit.record(actor, AuditEntry.success(catalog.name().toLowerCase() + ".created", catalog.name().toLowerCase(),
                    entry.id(), Map.of("code", code)));
            return new CatalogEntryView(entry.id(), entry.code(), entry.name(), entry.detail());
        });
    }

    /** Catalogs are readable by anyone who may read some part of the organization. */
    public List<CatalogEntryView> listCatalog(CurrentActor actor, Catalog catalog) {
        if (!guard.holdsAnywhere(actor, Permissions.ORG_READ)) {
            throw IamException.accessDenied();
        }
        return tx.readOnly(() -> store.listCatalog(catalog)).stream()
                .map(e -> new CatalogEntryView(e.id(), e.code(), e.name(), e.detail())).toList();
    }

    static OrgUnitView view(OrgUnit u) {
        return new OrgUnitView(u.id(), u.parentId(), u.kind().name(), u.code(), u.name(), u.path(), u.version());
    }
}
