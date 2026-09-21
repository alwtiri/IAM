package com.enterprise.iam.core.operation.application;

import com.enterprise.iam.core.operation.api.OperationView;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Scope-filtered read access to operations (spec §46, §52). */
public class OperationQueryService {

    /** Read port; scope attributes are denormalized on the operation row. */
    public interface OperationReader {
        List<OperationView> list(ScopeFilter filter, String status, PageRequest page);

        Optional<ScopedOperation> find(UUID id);
    }

    /** An operation with its scope attributes. */
    public record ScopedOperation(OperationView view, ResourceScope scope) {
    }

    private final OperationReader reader;
    private final AccessGuard guard;
    private final TransactionRunner tx;

    public OperationQueryService(OperationReader reader, AccessGuard guard, TransactionRunner tx) {
        this.reader = reader;
        this.guard = guard;
        this.tx = tx;
    }

    public PageResult<OperationView> list(CurrentActor actor, String status, PageRequest page) {
        ScopeFilter filter = guard.filter(actor, Permissions.OPERATION_READ);
        return tx.readOnly(() -> PageResult.fromOverfetch(reader.list(filter, status, page), page.limit(), OperationView::id));
    }

    public OperationView get(CurrentActor actor, UUID id) {
        ScopedOperation op = tx.readOnly(() -> reader.find(id)).orElseThrow(() -> IamException.notFound("Operation"));
        guard.require(actor, Permissions.OPERATION_READ, op.scope(), true);
        return op.view();
    }
}
