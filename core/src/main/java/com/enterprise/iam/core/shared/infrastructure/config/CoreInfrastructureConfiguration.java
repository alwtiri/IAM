package com.enterprise.iam.core.shared.infrastructure.config;

import com.enterprise.iam.core.shared.api.events.DomainEventPublisher;
import com.enterprise.iam.core.shared.api.security.StepUpPolicy;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared technical beans: clock, transactions, domain events, step-up policy, scheduling. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CoreInfrastructureConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TransactionRunner transactionRunner(PlatformTransactionManager txManager) {
        TransactionTemplate rw = new TransactionTemplate(txManager);
        rw.setTimeout(30);
        TransactionTemplate ro = new TransactionTemplate(txManager);
        ro.setReadOnly(true);
        ro.setTimeout(30);
        return new TransactionRunner() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> work) {
                return rw.execute(status -> work.get());
            }

            @Override
            public <T> T readOnly(java.util.function.Supplier<T> work) {
                return ro.execute(status -> work.get());
            }
        };
    }

    @Bean
    DomainEventPublisher domainEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    StepUpPolicy stepUpPolicy(@Value("${iam.auth.step-up.acr-values:mfa,2,gold}") Set<String> acrValues,
                              @Value("${iam.auth.step-up.amr-values:otp,mfa,hwk,swk}") Set<String> amrValues,
                              @Value("${iam.auth.step-up.max-age:PT5M}") Duration maxAge) {
        return new StepUpPolicy(acrValues, amrValues, maxAge);
    }
}
