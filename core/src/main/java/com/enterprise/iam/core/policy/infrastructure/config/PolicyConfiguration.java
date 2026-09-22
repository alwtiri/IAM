package com.enterprise.iam.core.policy.infrastructure.config;

import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.policy.application.PolicyService;
import com.enterprise.iam.core.policy.application.PolicyStore;
import com.enterprise.iam.core.policy.infrastructure.persistence.JdbcPolicyStore;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class PolicyConfiguration {

    @Bean
    PolicyStore policyStore(JdbcClient jdbc) {
        return new JdbcPolicyStore(jdbc);
    }

    @Bean
    PolicyService policyService(PolicyStore store, AccessGuard guard, AuditRecorder audit, TransactionRunner tx) {
        return new PolicyService(store, guard, audit, tx);
    }
}
