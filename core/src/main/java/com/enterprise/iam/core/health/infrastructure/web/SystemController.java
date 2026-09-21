package com.enterprise.iam.core.health.infrastructure.web;

import com.enterprise.iam.core.health.api.SystemHealthView;
import com.enterprise.iam.core.health.api.SystemInfoView;
import com.enterprise.iam.core.health.application.SystemService;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
class SystemController {

    private final SystemService system;
    private final CurrentActorProvider actors;

    SystemController(SystemService system, CurrentActorProvider actors) {
        this.system = system;
        this.actors = actors;
    }

    @GetMapping("/health")
    @RequiresPermission(Permissions.SYSTEM_HEALTH_READ)
    SystemHealthView health() {
        return system.health(actors.require());
    }

    @GetMapping("/info")
    @RequiresPermission(Permissions.SYSTEM_READ)
    SystemInfoView info() {
        return system.info(actors.require());
    }
}
