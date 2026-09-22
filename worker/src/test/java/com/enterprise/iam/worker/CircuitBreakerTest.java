package com.enterprise.iam.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.worker.runtime.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-22T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void opensAfterConsecutiveFailuresAndRecoversThroughOneTrial() {
        TestClock clock = new TestClock();
        CircuitBreaker b = new CircuitBreaker(20, 0.5, 5, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 5; i++) {
            assertTrue(b.tryAcquire());
            b.onFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        assertFalse(b.tryAcquire());
        clock.now = clock.now.plusSeconds(61);
        assertTrue(b.tryAcquire(), "one trial call when half-open");
        assertFalse(b.tryAcquire(), "only one trial at a time");
        b.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
    }

    @Test
    void failedTrialReopens() {
        TestClock clock = new TestClock();
        CircuitBreaker b = new CircuitBreaker(20, 0.5, 2, Duration.ofSeconds(10), clock);
        b.onFailure();
        b.onFailure();
        clock.now = clock.now.plusSeconds(11);
        assertTrue(b.tryAcquire());
        b.onFailure();
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }

    @Test
    void opensOnFailureRateOverTheWindow() {
        TestClock clock = new TestClock();
        CircuitBreaker b = new CircuitBreaker(10, 0.5, 100, Duration.ofSeconds(60), clock);
        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                b.onSuccess();
            } else {
                b.onFailure();
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, b.state());
    }
}
