package com.enterprise.iam.core.notification.infrastructure.config;

import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.shared.api.health.NetworkProbes;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.notification.application.NotificationService;
import com.enterprise.iam.core.notification.infrastructure.mail.SmtpDispatcher;
import com.enterprise.iam.core.operation.api.OutboxPublisher;
import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import com.enterprise.iam.core.shared.api.health.ComponentHealthCheck;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
class NotificationConfiguration {

    @Bean
    NotificationService notificationService(IdentityDirectory identities, OutboxPublisher outbox, JdbcClient jdbc, Clock clock,
                                            @Value("${spring.mail.host:}") String mailHost) {
        NotificationService.NotificationLog log = (id, channel, recipient, template, subject) -> jdbc.sql("""
                INSERT INTO notification.notification (id, channel, recipient, template_key, subject, status, created_at)
                VALUES (:id, :channel, :recipient, :template, :subject, 'QUEUED', now())""")
                .param("id", id).param("channel", channel).param("recipient", recipient).param("template", template)
                .param("subject", subject).update();
        return new NotificationService(identities, outbox, log, clock, !mailHost.isBlank());
    }

    /** Registered only when e-mail is configured (spring.mail.host set), so no unusable dispatcher exists. */
    @Bean
    @ConditionalOnExpression("!'${spring.mail.host:}'.isBlank()")
    SmtpDispatcher smtpDispatcher(ObjectProvider<JavaMailSender> mail, JdbcClient jdbc, @Value("${iam.mail.from:iam@localhost}") String from) {
        return new SmtpDispatcher(mail.getObject(), jdbc, from);
    }

    @Bean
    ComponentHealthCheck smtpHealthCheck(@Value("${spring.mail.host:}") String host, @Value("${spring.mail.port:25}") int port) {
        if (host.isBlank()) {
            return new ComponentHealthCheck() {
                @Override
                public String component() {
                    return "smtp";
                }

                @Override
                public ComponentHealth.Category category() {
                    return ComponentHealth.Category.INTEGRATION;
                }

                @Override
                public ComponentHealth.Classification classification() {
                    return ComponentHealth.Classification.OPTIONAL;
                }

                @Override
                public List<String> affectedFunctionality() {
                    return List.of("E-mail notifications are not sent (SMTP not configured)");
                }

                @Override
                public Result check() {
                    return new Result(ComponentHealth.Status.UNKNOWN, "not configured");
                }
            };
        }
        return NetworkProbes.tcp("smtp", ComponentHealth.Category.INTEGRATION, ComponentHealth.Classification.OPTIONAL, host, port, "220",
                List.of("E-mail notifications are delayed (queued in the outbox)"));
    }

    @Bean
    NotificationListeners notificationListeners(NotificationService service) {
        return new NotificationListeners(service);
    }

    /** Synchronous listeners: outbox rows commit or roll back with the originating change. */
    static class NotificationListeners {
        private final NotificationService service;

        NotificationListeners(NotificationService service) {
            this.service = service;
        }

        @EventListener
        void onRoleAssignment(RoleAssignmentChanged e) {
            service.on(e);
        }

        @EventListener
        void onIdentityLifecycle(IdentityLifecycleChanged e) {
            service.on(e);
        }
    }
}
