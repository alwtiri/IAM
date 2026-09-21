package com.enterprise.iam.core.shared.api.health;

import java.time.Duration;
import java.util.List;

/**
 * A health probe contributed by a module (e.g. the secrets module contributes Vault). Implementations should use
 * their own short network timeouts; the aggregator additionally enforces {@link #timeout()} and runs probes in
 * parallel so a hanging dependency cannot delay others (RES6).
 */
public interface ComponentHealthCheck {

    String component();

    ComponentHealth.Category category();

    ComponentHealth.Classification classification();

    /** Functionality that is unavailable or degraded when this component is not HEALTHY. */
    List<String> affectedFunctionality();

    default Duration timeout() {
        return Duration.ofSeconds(3);
    }

    /** Performs the probe. May throw; the aggregator converts exceptions to UNAVAILABLE. */
    Result check() throws Exception;

    record Result(ComponentHealth.Status status, String reason) {
        public static Result healthy() {
            return new Result(ComponentHealth.Status.HEALTHY, null);
        }

        public static Result unavailable(String reason) {
            return new Result(ComponentHealth.Status.UNAVAILABLE, reason);
        }

        public static Result degraded(String reason) {
            return new Result(ComponentHealth.Status.DEGRADED, reason);
        }
    }
}
