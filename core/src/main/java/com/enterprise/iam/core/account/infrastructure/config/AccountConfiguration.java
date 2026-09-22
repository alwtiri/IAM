package com.enterprise.iam.core.account.infrastructure.config;

import com.enterprise.iam.core.account.application.AccountOperationService;
import com.enterprise.iam.core.account.application.AccountService;
import com.enterprise.iam.core.account.application.AccountStore;
import com.enterprise.iam.core.account.infrastructure.persistence.JdbcAccountStore;
import com.enterprise.iam.core.audit.api.AuditRecorder;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.operation.api.OperationCommands;
import com.enterprise.iam.core.operation.api.OperationCompleted;
import com.enterprise.iam.core.operation.api.OperationProgress;
import com.enterprise.iam.core.provider.api.ProviderDirectory;
import com.enterprise.iam.core.secrets.api.CredentialHandles;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import com.enterprise.iam.core.target.api.TargetDirectory;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class AccountConfiguration {

    @Bean
    AccountStore accountStore(JdbcClient jdbc) {
        return new JdbcAccountStore(jdbc);
    }

    @Bean
    AccountService accountService(AccountStore store, IdentityDirectory identities, AccessGuard guard, AuditRecorder audit,
                                  TransactionRunner tx, Clock clock, @Value("${iam.accounts.dormant-after-days:90}") int dormantDays) {
        return new AccountService(store, identities, guard, audit, tx, clock, Duration.ofDays(dormantDays));
    }

    @Bean
    AccountOperationService accountOperationService(AccountService accounts, AccountStore store, OperationCommands operations,
                                                    CredentialHandles handles, ProviderDirectory providers, TargetDirectory targets,
                                                    AccessGuard guard, AuditRecorder audit, TransactionRunner tx) {
        return new AccountOperationService(accounts, store, operations, handles, providers, targets, guard, audit, tx);
    }

    @Bean
    OperationResultListeners accountOperationResultListeners(AccountOperationService service) {
        return new OperationResultListeners(service);
    }

    /** Synchronous listeners: imports commit or roll back together with the operation result. */
    static class OperationResultListeners {
        private final AccountOperationService service;

        OperationResultListeners(AccountOperationService service) {
            this.service = service;
        }

        @EventListener
        void onProgress(OperationProgress e) {
            service.onProgress(e);
        }

        @EventListener
        void onCompleted(OperationCompleted e) {
            service.onCompleted(e);
        }
    }
}
