package com.enterprise.iam.core.account.infrastructure.config;

import com.enterprise.iam.core.account.application.AccountService;
import com.enterprise.iam.core.account.infrastructure.persistence.JdbcAccountStore;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class AccountConfiguration {

    @Bean
    AccountService accountService(JdbcClient jdbc, IdentityDirectory identities, AccessGuard guard, AuditRecorder audit,
                                  TransactionRunner tx, Clock clock, @Value("${iam.accounts.dormant-after-days:90}") int dormantDays) {
        return new AccountService(new JdbcAccountStore(jdbc), identities, guard, audit, tx, clock, Duration.ofDays(dormantDays));
    }
}
