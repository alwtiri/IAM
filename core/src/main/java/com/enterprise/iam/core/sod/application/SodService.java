package com.enterprise.iam.core.sod.application;

import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.sod.api.SodChecker;
import com.enterprise.iam.core.sod.api.SodConflict;
import com.enterprise.iam.core.sod.api.SodRuleView;
import java.util.List;

public class SodService implements SodChecker {

    private final SodStore store;
    private final AccessGuard guard;
    private final TransactionRunner tx;

    public SodService(SodStore store, AccessGuard guard, TransactionRunner tx) {
        this.store = store;
        this.guard = guard;
        this.tx = tx;
    }

    @Override
    public List<SodConflict> check(List<String> heldRoles, String requestedRole) {
        return tx.readOnly(store::rules).stream().flatMap(r -> r.conflict(heldRoles, requestedRole).stream()).toList();
    }

    public List<SodRuleView> list(CurrentActor actor) {
        guard.require(actor, Permissions.SOD_READ, ResourceScope.PLATFORM, false);
        return tx.readOnly(store::rules).stream()
                .map(r -> new SodRuleView(r.id(), r.code(), r.name(), r.leftRole(), r.rightRole(), r.mode(), r.severity(), r.enabled())).toList();
    }
}
