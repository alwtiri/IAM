package com.enterprise.iam.core.sod.infrastructure.config;

import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.sod.application.SodService;
import com.enterprise.iam.core.sod.application.SodStore;
import com.enterprise.iam.core.sod.infrastructure.persistence.JdbcSodStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class SodConfiguration {

    @Bean
    SodStore sodStore(JdbcClient jdbc) {
        return new JdbcSodStore(jdbc);
    }

    @Bean
    SodService sodService(SodStore store, AccessGuard guard, TransactionRunner tx) {
        return new SodService(store, guard, tx);
    }
}
