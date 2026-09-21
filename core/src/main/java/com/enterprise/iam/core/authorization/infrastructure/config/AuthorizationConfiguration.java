package com.enterprise.iam.core.authorization.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.authorization.application.AccessGuardService;
import com.enterprise.iam.core.authorization.application.AuthorizationStore;
import com.enterprise.iam.core.authorization.application.RoleAssignmentService;
import com.enterprise.iam.core.authorization.infrastructure.persistence.JdbcAuthorizationStore;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
class AuthorizationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationConfiguration.class);

    @Bean
    AuthorizationStore authorizationStore(JdbcClient jdbc) {
        return new JdbcAuthorizationStore(jdbc);
    }

    @Bean
    AccessGuardService accessGuard(AuthorizationStore store, OrganizationDirectory org, Clock clock) {
        return new AccessGuardService(store, org, clock);
    }

    @Bean
    RoleAssignmentService roleAssignmentService(AuthorizationStore store, IdentityDirectory identities, OrganizationDirectory org,
                                                AccessGuard guard, AuditRecorder audit, DomainEventPublisher events,
                                                TransactionRunner tx, Clock clock) {
        return new RoleAssignmentService(store, identities, org, guard, audit, events, tx, clock);
    }

    @Bean
    RoleAssignmentExpiryJob roleAssignmentExpiryJob(RoleAssignmentService service) {
        return new RoleAssignmentExpiryJob(service);
    }

    /** Marks expired assignments (S11); decisions ignore them regardless. SKIP LOCKED makes it replica-safe. */
    static class RoleAssignmentExpiryJob {
        private final RoleAssignmentService service;

        RoleAssignmentExpiryJob(RoleAssignmentService service) {
            this.service = service;
        }

        @Scheduled(fixedDelayString = "${iam.expiry.interval:PT1M}", initialDelayString = "PT45S")
        void run() {
            try {
                int n = service.expireDue(200);
                if (n > 0) {
                    log.info("Expired {} role assignments", n);
                }
            } catch (RuntimeException e) {
                log.warn("Role assignment expiry pass failed; will retry", e);
            }
        }
    }
}
