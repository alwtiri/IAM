package com.enterprise.iam.core.identity.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.application.ActorResolver;
import com.enterprise.iam.core.identity.application.IdentityService;
import com.enterprise.iam.core.identity.application.IdentityStore;
import com.enterprise.iam.core.identity.application.PersonService;
import com.enterprise.iam.core.identity.infrastructure.persistence.JdbcIdentityStore;
import com.enterprise.iam.core.identity.infrastructure.security.SpringSecurityActorProvider;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.BootstrapAdministratorGrant;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdentityConfiguration.class);

    @Bean
    IdentityStore identityStore(JdbcClient jdbc) {
        return new JdbcIdentityStore(jdbc);
    }

    @Bean
    PersonService personService(IdentityStore store, OrganizationDirectory org, AccessGuard guard, AuditRecorder audit,
                                TransactionRunner tx, Clock clock) {
        return new PersonService(store, org, guard, audit, tx, clock);
    }

    @Bean
    IdentityService identityService(IdentityStore store, AccessGuard guard, AuditRecorder audit, DomainEventPublisher events,
                                    TransactionRunner tx, Clock clock) {
        return new IdentityService(store, guard, audit, events, tx, clock);
    }

    @Bean
    ActorResolver actorResolver(IdentityStore store, BootstrapAdministratorGrant grant, AuditRecorder audit, TransactionRunner tx,
                                Clock clock, @Value("${iam.bootstrap.admin-subject:}") String bootstrapSubject) {
        return new ActorResolver(store, grant, audit, tx, clock, bootstrapSubject);
    }

    @Bean
    SpringSecurityActorProvider currentActorProvider(ActorResolver resolver) {
        return new SpringSecurityActorProvider(resolver);
    }

    @Bean
    IdentityExpiryJob identityExpiryJob(IdentityService identities) {
        return new IdentityExpiryJob(identities);
    }

    /** Disables identities past their validity (S11). Rows are claimed with SKIP LOCKED, so replicas never collide. */
    static class IdentityExpiryJob {
        private final IdentityService identities;

        IdentityExpiryJob(IdentityService identities) {
            this.identities = identities;
        }

        @Scheduled(fixedDelayString = "${iam.expiry.interval:PT1M}", initialDelayString = "PT30S")
        void run() {
            try {
                int n = identities.expireDue(200);
                if (n > 0) {
                    log.info("Disabled {} identities past their validity", n);
                }
            } catch (RuntimeException e) {
                log.warn("Identity expiry pass failed; will retry", e);
            }
        }
    }
}
