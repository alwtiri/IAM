package com.enterprise.iam.core.organization.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.organization.application.OrganizationDirectoryService;
import com.enterprise.iam.core.organization.application.OrganizationService;
import com.enterprise.iam.core.organization.application.OrganizationStore;
import com.enterprise.iam.core.organization.infrastructure.persistence.JdbcOrganizationStore;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class OrganizationConfiguration {

    @Bean
    OrganizationStore organizationStore(JdbcClient jdbc) {
        return new JdbcOrganizationStore(jdbc);
    }

    @Bean
    OrganizationDirectoryService organizationDirectory(OrganizationStore store) {
        return new OrganizationDirectoryService(store);
    }

    @Bean
    OrganizationService organizationService(OrganizationStore store, AccessGuard guard, AuditRecorder audit, TransactionRunner tx, Clock clock) {
        return new OrganizationService(store, guard, audit, tx, clock);
    }
}
