package com.enterprise.iam.core.sod.infrastructure.web;

import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.sod.api.SodRuleView;
import com.enterprise.iam.core.sod.application.SodService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SodController {

    private final SodService sod;
    private final CurrentActorProvider actors;

    SodController(SodService sod, CurrentActorProvider actors) {
        this.sod = sod;
        this.actors = actors;
    }

    @GetMapping("/api/v1/sod-rules")
    @RequiresPermission(Permissions.SOD_READ)
    List<SodRuleView> list() {
        return sod.list(actors.require());
    }
}
