package com.enterprise.iam.core.testsupport;

import com.enterprise.iam.core.shared.api.context.RequestContext;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.ResourceScope;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.kernel.IamException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Plain-Java test doubles for application-service tests (no Spring context). */
public final class TestSupport {

    private TestSupport() {
    }

    public static final TransactionRunner DIRECT_TX = new TransactionRunner() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }

        @Override
        public <T> T readOnly(Supplier<T> work) {
            return work.get();
        }
    };

    public static final RequestContextProvider CONTEXT = () -> new RequestContext("test-correlation-1", "10.0.0.5", "junit");

    public static CurrentActor actor(UUID identityId) {
        return new CurrentActor(identityId, "sub-" + identityId, "mfa", List.of("otp"), Instant.now(),
                CurrentActor.Channel.BROWSER_SESSION, "10.0.0.5");
    }

    /** Mutable clock for time-dependent tests. */
    public static final class MutableClock extends Clock {
        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

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

    /** Guard that allows or denies everything. */
    public static AccessGuard guard(boolean allow) {
        return new AccessGuard() {
            @Override
            public boolean isAllowed(CurrentActor actor, String permission, ResourceScope resource) {
                return allow;
            }

            @Override
            public void require(CurrentActor actor, String permission, ResourceScope resource, boolean hideExistence) {
                if (!allow) {
                    throw hideExistence ? IamException.notFound("Object") : IamException.accessDenied();
                }
            }

            @Override
            public boolean holdsAnywhere(CurrentActor actor, String permission) {
                return allow;
            }

            @Override
            public ScopeFilter filter(CurrentActor actor, String permission) {
                return allow ? ScopeFilter.GLOBAL : ScopeFilter.NONE;
            }
        };
    }
}
