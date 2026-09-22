package com.enterprise.iam.core.request.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.authorization.api.RoleDirectory;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.policy.api.PolicyDecisionPoint;
import com.enterprise.iam.core.request.application.AccessRequestService;
import com.enterprise.iam.core.request.application.RequestStore;
import com.enterprise.iam.core.request.infrastructure.persistence.JdbcRequestStore;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.sod.api.SodChecker;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Configuration(proxyBeanMethods = false)
class RequestConfiguration {

    @Bean
    RequestStore requestStore(JdbcClient jdbc) {
        return new JdbcRequestStore(jdbc);
    }

    @Bean
    AccessRequestService accessRequestService(RequestStore store, RoleDirectory roles, IdentityDirectory identities, PolicyDecisionPoint pdp,
                                              SodChecker sod, AccessGuard guard, AuditRecorder audit, DomainEventPublisher events,
                                              TransactionRunner tx, Clock clock) {
        return new AccessRequestService(store, roles, identities, pdp, sod, guard, audit, events, tx, clock);
    }

    @Bean
    AssignmentSync assignmentSync(AccessRequestService requests) {
        return new AssignmentSync(requests);
    }

    /** Marks ACTIVE requests EXPIRED/REVOKED when their role assignment ends. */
    static class AssignmentSync {
        private final AccessRequestService requests;

        AssignmentSync(AccessRequestService requests) {
            this.requests = requests;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void on(RoleAssignmentChanged e) {
            requests.onAssignmentChanged(e.assignmentId(), e.change());
        }
    }
}
