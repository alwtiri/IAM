package com.enterprise.iam.core.operation.domain;

import java.time.Duration;

/** Exponential back-off for outbox delivery: base × 2^(attempt-1), capped; park after {@code maxAttempts}. */
public record RetryPolicy(Duration base, Duration cap, int maxAttempts) {

    public static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(15), 20);

    public Duration delayAfter(int attempts) {
        int exp = Math.min(Math.max(attempts - 1, 0), 20);
        Duration d = base.multipliedBy(1L << exp);
        return d.compareTo(cap) > 0 ? cap : d;
    }

    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
