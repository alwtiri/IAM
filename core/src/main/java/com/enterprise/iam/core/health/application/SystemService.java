package com.enterprise.iam.core.health.application;

import com.enterprise.iam.core.health.api.SystemHealthView;
import com.enterprise.iam.core.health.api.SystemInfoView;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.kernel.IamException;
import java.util.List;

public class SystemService {

    private final HealthAggregator health;
    private final AccessGuard guard;
    private final SystemInfoView info;

    public SystemService(HealthAggregator health, AccessGuard guard, String version, String buildSha, List<String> enabledExtensions) {
        this.health = health;
        this.guard = guard;
        this.info = new SystemInfoView(version, "v1", buildSha, List.copyOf(enabledExtensions));
    }

    public SystemHealthView health(CurrentActor actor) {
        if (!guard.holdsAnywhere(actor, Permissions.SYSTEM_HEALTH_READ)) {
            throw IamException.accessDenied();
        }
        return health.current();
    }

    public SystemInfoView info(CurrentActor actor) {
        if (!guard.holdsAnywhere(actor, Permissions.SYSTEM_READ)) {
            throw IamException.accessDenied();
        }
        return info;
    }
}
