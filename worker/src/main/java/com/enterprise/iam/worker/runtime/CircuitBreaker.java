package com.enterprise.iam.worker.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-provider-instance circuit breaker (WORKER-ISOLATION §3): opens after {@code consecutiveThreshold} consecutive
 * connection failures or when the failure rate over the last {@code window} calls reaches {@code rateThreshold};
 * after {@code openFor} one trial call is allowed (half-open). A slow or dead target therefore fails fast instead of
 * tying up pool capacity that other targets need (G4).
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int window;
    private final double rateThreshold;
    private final int consecutiveThreshold;
    private final Duration openFor;
    private final Clock clock;
    private final Deque<Boolean> outcomes = new ArrayDeque<>();
    private int consecutiveFailures;
    private State state = State.CLOSED;
    private Instant openedAt;
    private boolean trialInFlight;

    public CircuitBreaker(int window, double rateThreshold, int consecutiveThreshold, Duration openFor, Clock clock) {
        this.window = window;
        this.rateThreshold = rateThreshold;
        this.consecutiveThreshold = consecutiveThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    public static CircuitBreaker defaults(Clock clock) {
        return new CircuitBreaker(20, 0.5, 5, Duration.ofSeconds(60), clock);
    }

    /** @return true if a call may proceed now */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openFor))) {
            state = State.HALF_OPEN;
            trialInFlight = false;
        }
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (trialInFlight) {
                    yield false;
                }
                trialInFlight = true;
                yield true;
            }
        };
    }

    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        record(true);
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            outcomes.clear();
        }
        trialInFlight = false;
    }

    public synchronized void onFailure() {
        consecutiveFailures++;
        record(false);
        long failures = outcomes.stream().filter(ok -> !ok).count();
        boolean rateTripped = outcomes.size() >= window && failures >= Math.ceil(rateThreshold * window);
        if (state == State.HALF_OPEN || consecutiveFailures >= consecutiveThreshold || rateTripped) {
            state = State.OPEN;
            openedAt = clock.instant();
        }
        trialInFlight = false;
    }

    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openFor))) {
            return State.HALF_OPEN;
        }
        return state;
    }

    private void record(boolean ok) {
        outcomes.addLast(ok);
        while (outcomes.size() > window) {
            outcomes.removeFirst();
        }
    }
}
