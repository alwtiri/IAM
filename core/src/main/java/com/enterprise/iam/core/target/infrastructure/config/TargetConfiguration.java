package com.enterprise.iam.core.target.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.organization.api.OrganizationDirectory;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.target.application.TargetService;
import com.enterprise.iam.core.target.infrastructure.persistence.JdbcTargetStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class TargetConfiguration {

    @Bean
    TargetService targetService(JdbcClient jdbc, OrganizationDirectory org, AccessGuard guard, AuditRecorder audit, TransactionRunner tx, Clock clock) {
        return new TargetService(new JdbcTargetStore(jdbc), org, guard, audit, tx, clock);
    }
}
