package com.enterprise.iam.core.operation.infrastructure.web;

import com.enterprise.iam.core.operation.api.OperationView;
import com.enterprise.iam.core.operation.application.OperationQueryService;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
class OperationController {

    private final OperationQueryService operations;
    private final CurrentActorProvider actors;

    OperationController(OperationQueryService operations, CurrentActorProvider actors) {
        this.operations = operations;
        this.actors = actors;
    }

    @GetMapping
    @RequiresPermission(Permissions.OPERATION_READ)
    PageResult<OperationView> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) Integer limit,
                                   @RequestParam(required = false) String cursor) {
        return operations.list(actors.require(), status, PageRequest.of(limit, cursor));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.OPERATION_READ)
    OperationView get(@PathVariable UUID id) {
        return operations.get(actors.require(), id);
    }
}
