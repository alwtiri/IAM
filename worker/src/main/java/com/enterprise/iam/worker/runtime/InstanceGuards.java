package com.enterprise.iam.worker.runtime;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Bulkhead + circuit breaker per provider instance (WORKER-ISOLATION §3): instances never share capacity, and one
 * failing instance cannot block another instance of the same type.
 */
public final class InstanceGuards {

    public record Guard(CircuitBreaker breaker, Semaphore bulkhead) {
    }

    private final Map<UUID, Guard> guards = new ConcurrentHashMap<>();
    private final Supplier<CircuitBreaker> breakers;
    private final int bulkheadSize;

    public InstanceGuards(Clock clock, int bulkheadSize) {
        this(() -> CircuitBreaker.defaults(clock), bulkheadSize);
    }

    public InstanceGuards(Supplier<CircuitBreaker> breakers, int bulkheadSize) {
        this.breakers = breakers;
        this.bulkheadSize = bulkheadSize;
    }

    public Guard forInstance(UUID providerInstanceId) {
        return guards.computeIfAbsent(providerInstanceId, id -> new Guard(breakers.get(), new Semaphore(bulkheadSize, true)));
    }

    /** Current breaker states, for health reporting. */
    public Map<UUID, CircuitBreaker.State> states() {
        Map<UUID, CircuitBreaker.State> out = new java.util.HashMap<>();
        guards.forEach((id, g) -> out.put(id, g.breaker().state()));
        return out;
    }
}
