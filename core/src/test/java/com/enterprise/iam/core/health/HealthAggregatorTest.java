package com.enterprise.iam.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.health.api.SystemHealthView;
import com.enterprise.iam.core.health.application.HealthAggregator;
import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import com.enterprise.iam.core.shared.api.health.ComponentHealthCheck;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** RES6: a hanging or failing dependency never delays or hides the others. */
class HealthAggregatorTest {

    private static ComponentHealthCheck check(String name, ComponentHealth.Classification c, Duration timeout, ComponentHealthCheck.Result r,
                                              long sleepMillis, boolean throwIt) {
        return new ComponentHealthCheck() {
            @Override
            public String component() {
                return name;
            }

            @Override
            public ComponentHealth.Category category() {
                return ComponentHealth.Category.INTEGRATION;
            }

            @Override
            public ComponentHealth.Classification classification() {
                return c;
            }

            @Override
            public List<String> affectedFunctionality() {
                return List.of(name + " features");
            }

            @Override
            public Duration timeout() {
                return timeout;
            }

            @Override
            public Result check() throws Exception {
                Thread.sleep(sleepMillis);
                if (throwIt) {
                    throw new IllegalStateException("boom");
                }
                return r;
            }
        };
    }

    @Test
    void hangingOptionalCheckTimesOutWithoutBlockingOthers() {
        HealthAggregator agg = new HealthAggregator(List.of(
                check("db", ComponentHealth.Classification.CORE_DEPENDENCY, Duration.ofSeconds(1), ComponentHealthCheck.Result.healthy(), 0, false),
                check("slow-smtp", ComponentHealth.Classification.OPTIONAL, Duration.ofMillis(200), ComponentHealthCheck.Result.healthy(), 5_000, false),
                check("broken-cache", ComponentHealth.Classification.OPTIONAL, Duration.ofSeconds(1), null, 0, true)),
                Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), Duration.ZERO);
        long start = System.nanoTime();
        SystemHealthView v = agg.current();
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 2_000, "aggregation bounded by per-check timeouts, took " + millis);
        assertEquals(ComponentHealth.Status.DEGRADED, v.status());
        ComponentHealth slow = v.components().stream().filter(c -> c.component().equals("slow-smtp")).findFirst().orElseThrow();
        assertEquals(ComponentHealth.Status.UNAVAILABLE, slow.status());
        assertTrue(slow.reason().contains("timed out"));
        assertEquals(List.of("slow-smtp features"), slow.affectedFunctionality());
        ComponentHealth db = v.components().stream().filter(c -> c.component().equals("db")).findFirst().orElseThrow();
        assertTrue(db.affectedFunctionality().isEmpty(), "healthy components list no affected functionality");
    }

    @Test
    void coreDependencyOutageMakesOverallUnavailable() {
        HealthAggregator agg = new HealthAggregator(List.of(
                check("vault", ComponentHealth.Classification.CORE_DEPENDENCY, Duration.ofSeconds(1), ComponentHealthCheck.Result.unavailable("sealed"), 0, false)),
                Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), Duration.ofSeconds(5));
        assertEquals(ComponentHealth.Status.UNAVAILABLE, agg.current().status());
    }
}
