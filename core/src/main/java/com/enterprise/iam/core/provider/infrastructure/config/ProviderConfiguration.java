package com.enterprise.iam.core.provider.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.provider.application.ProviderRegistryService;
import com.enterprise.iam.core.provider.infrastructure.persistence.JdbcProviderStore;
import com.enterprise.iam.core.secrets.api.SecretStore;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {

    @Bean
    ProviderRegistryService providerRegistryService(JdbcClient jdbc, SecretStore secrets, AccessGuard guard, AuditRecorder audit,
                                                    TransactionRunner tx, Clock clock) {
        return new ProviderRegistryService(new JdbcProviderStore(jdbc), secrets, guard, audit, tx, clock);
    }
}
