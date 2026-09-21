package com.enterprise.iam.core.health.application;

import com.enterprise.iam.core.health.api.SystemHealthView;
import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import com.enterprise.iam.core.shared.api.health.ComponentHealthCheck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs all contributed health checks in parallel, each bounded by its own timeout (RES6), and caches the result for a
 * short period so the endpoint cannot be used to hammer dependencies. Overall status: UNAVAILABLE if a CORE or
 * CORE_DEPENDENCY component is unavailable, DEGRADED if anything else is not healthy, otherwise HEALTHY.
 */
public class HealthAggregator {

    private final List<ComponentHealthCheck> checks;
    private final ExecutorService executor;
    private final Clock clock;
    private final Duration cacheTtl;
    private volatile SystemHealthView cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public HealthAggregator(List<ComponentHealthCheck> checks, ExecutorService executor, Clock clock, Duration cacheTtl) {
        this.checks = List.copyOf(checks);
        this.executor = executor;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    public SystemHealthView current() {
        Instant now = clock.instant();
        SystemHealthView c = cached;
        if (c != null && now.isBefore(cachedAt.plus(cacheTtl))) {
            return c;
        }
        SystemHealthView fresh = evaluate();
        cached = fresh;
        cachedAt = now;
        return fresh;
    }

    SystemHealthView evaluate() {
        List<CompletableFuture<ComponentHealth>> futures = new ArrayList<>();
        for (ComponentHealthCheck check : checks) {
            futures.add(CompletableFuture.supplyAsync(() -> run(check), executor)
                    .completeOnTimeout(result(check, ComponentHealth.Status.UNAVAILABLE, "timed out after " + check.timeout().toMillis() + " ms"),
                            check.timeout().toMillis(), TimeUnit.MILLISECONDS));
        }
        List<ComponentHealth> results = futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparing(ComponentHealth::component)).toList();
        return new SystemHealthView(overall(results), results);
    }

    private ComponentHealth run(ComponentHealthCheck check) {
        try {
            ComponentHealthCheck.Result r = check.check();
            return result(check, r.status(), r.reason());
        } catch (Exception e) {
            return result(check, ComponentHealth.Status.UNAVAILABLE, e.getClass().getSimpleName());
        }
    }

    private ComponentHealth result(ComponentHealthCheck check, ComponentHealth.Status status, String reason) {
        return new ComponentHealth(check.component(), check.category(), check.classification(), status, reason,
                check.affectedFunctionality(), clock.instant());
    }

    static ComponentHealth.Status overall(List<ComponentHealth> results) {
        boolean coreDown = results.stream().anyMatch(r -> r.status() == ComponentHealth.Status.UNAVAILABLE
                && (r.classification() == ComponentHealth.Classification.CORE || r.classification() == ComponentHealth.Classification.CORE_DEPENDENCY));
        if (coreDown) {
            return ComponentHealth.Status.UNAVAILABLE;
        }
        return results.stream().allMatch(r -> r.status() == ComponentHealth.Status.HEALTHY) ? ComponentHealth.Status.HEALTHY
                : ComponentHealth.Status.DEGRADED;
    }
}
