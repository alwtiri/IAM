package com.enterprise.iam.core.audit.infrastructure.config;

import com.enterprise.iam.core.audit.application.AuditService;
import com.enterprise.iam.core.audit.infrastructure.persistence.JdbcAuditStore;
import com.enterprise.iam.core.shared.api.context.RequestContextProvider;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.tx.TransactionRunner;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class AuditConfiguration {

    /** AccessGuard is injected lazily: the authorization module itself records audit events (no construction cycle). */
    @Bean
    AuditService auditService(JdbcClient jdbc, RequestContextProvider ctx, @Lazy AccessGuard guard, TransactionRunner tx, Clock clock) {
        return new AuditService(new JdbcAuditStore(jdbc), ctx, guard, tx, clock);
    }

    /** Audit forwarding to a SIEM webhook; idle unless {@code iam.siem.webhook-url} is set. */
    @Bean
    com.enterprise.iam.core.audit.infrastructure.siem.SiemForwarder siemForwarder(JdbcClient jdbc,
            @org.springframework.beans.factory.annotation.Value("${iam.siem.webhook-url:}") String url,
            @org.springframework.beans.factory.annotation.Value("${iam.siem.webhook-secret:}") String secret) {
        return new com.enterprise.iam.core.audit.infrastructure.siem.SiemForwarder(jdbc, url == null || url.isBlank() ? null : java.net.URI.create(url.trim()), secret);
    }
}
