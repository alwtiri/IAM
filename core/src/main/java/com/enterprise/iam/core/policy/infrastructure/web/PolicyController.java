package com.enterprise.iam.core.policy.infrastructure.web;

import com.enterprise.iam.core.policy.api.PolicyView;
import com.enterprise.iam.core.policy.application.PolicyService;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyController {

    private final PolicyService policies;
    private final CurrentActorProvider actors;

    PolicyController(PolicyService policies, CurrentActorProvider actors) {
        this.policies = policies;
        this.actors = actors;
    }

    @GetMapping
    @RequiresPermission(Permissions.POLICY_READ)
    List<PolicyView> list() {
        return policies.list(actors.require());
    }

    @PostMapping("/{id}:enable")
    @RequiresPermission(Permissions.POLICY_WRITE)
    @RequiresStepUp
    PolicyView enable(@PathVariable UUID id) {
        return policies.setEnabled(actors.require(), id, true);
    }

    @PostMapping("/{id}:disable")
    @RequiresPermission(Permissions.POLICY_WRITE)
    @RequiresStepUp
    PolicyView disable(@PathVariable UUID id) {
        return policies.setEnabled(actors.require(), id, false);
    }
}
