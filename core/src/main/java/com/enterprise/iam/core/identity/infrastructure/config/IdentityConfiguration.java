package com.enterprise.iam.core.identity.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.application.ActorResolver;
import com.enterprise.iam.core.identity.application.IdentityService;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.identity.application.IdentityStore;
import com.enterprise.iam.core.identity.application.LoginAccountProvisioner;
import com.enterprise.iam.core.identity.application.PlatformLoginService;
import com.enterprise.iam.core.identity.infrastructure.keycloak.KeycloakLoginProvisioner;
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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    LoginAccountProvisioner loginAccountProvisioner(@Value("${iam.auth.admin.base-url:http://keycloak:8080/auth}") String baseUrl,
                                                    @Value("${iam.auth.admin.realm:iam}") String realm,
                                                    @Value("${iam.auth.admin.client-id:iam-core-admin}") String clientId,
                                                    @Value("${iam.auth.admin-client-secret:}") String clientSecret,
                                                    @Value("${iam.auth.client-id:iam-core}") String loginClientId,
                                                    @Value("${iam.auth.post-logout-redirect-uri:http://localhost:8088/}") String redirectUri) {
        KeycloakLoginProvisioner p = new KeycloakLoginProvisioner(baseUrl, realm, clientId, clientSecret, loginClientId, redirectUri);
        log.info("Login provisioning through the Keycloak admin API is {}", p.enabled() ? "enabled" : "disabled (no admin client secret)");
        return p;
    }

    @Bean
    PlatformLoginService platformLoginService(IdentityService identities, IdentityStore store, LoginAccountProvisioner provisioner,
                                              AccessGuard guard, AuditRecorder audit, TransactionRunner tx) {
        return new PlatformLoginService(identities, store, provisioner, guard, audit, tx);
    }

    @Bean
    LoginLifecycleSync loginLifecycleSync(PlatformLoginService logins) {
        return new LoginLifecycleSync(logins);
    }

    /** Suspended or disabled identities are also blocked at Keycloak; reinstated ones are unblocked (after commit). */
    static class LoginLifecycleSync {
        private final PlatformLoginService logins;

        LoginLifecycleSync(PlatformLoginService logins) {
            this.logins = logins;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        void on(IdentityLifecycleChanged e) {
            try {
                logins.onLifecycleChanged(e.identityId(), e.toState());
            } catch (RuntimeException ex) {
                log.warn("Could not update the Keycloak account of identity {}: {}", e.identityId(), ex.getMessage());
            }
        }
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
