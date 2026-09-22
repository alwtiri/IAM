package com.enterprise.iam.core.notification.infrastructure.config;

import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.shared.api.health.NetworkProbes;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.request.api.AccessRequestChanged;
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
    WeeklyReportJob weeklyReportJob(NotificationService service, com.enterprise.iam.core.authorization.api.RoleDirectory roles,
                                    org.springframework.jdbc.core.simple.JdbcClient jdbc, Clock clock) {
        return new WeeklyReportJob(service, roles, jdbc, clock);
    }

    /**
     * Weekly security summary e-mail (Phase 7). Read-only counts across schemas, the reporting pattern used for exports;
     * nothing is written except the outbox rows of the e-mails.
     */
    static class WeeklyReportJob {
        private final NotificationService service;
        private final com.enterprise.iam.core.authorization.api.RoleDirectory roles;
        private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
        private final Clock clock;

        WeeklyReportJob(NotificationService service, com.enterprise.iam.core.authorization.api.RoleDirectory roles,
                        org.springframework.jdbc.core.simple.JdbcClient jdbc, Clock clock) {
            this.service = service;
            this.roles = roles;
            this.jdbc = jdbc;
            this.clock = clock;
        }

        @org.springframework.scheduling.annotation.Scheduled(cron = "${iam.reports.weekly-cron:0 0 7 * * MON}")
        void send() {
            java.time.LocalDate today = java.time.LocalDate.now(clock);
            java.util.Map<String, String> f = new java.util.HashMap<>();
            f.put("period", today.minusDays(7) + " – " + today);
            f.put("targets", count("SELECT count(*) FROM target.target WHERE status <> 'DECOMMISSIONED'"));
            f.put("privileged", count("SELECT count(*) FROM account.account a JOIN target.target t ON t.id = a.target_id "
                    + "WHERE a.privileged AND a.native_status <> 'ABSENT' AND t.status <> 'DECOMMISSIONED'"));
            f.put("vaulted", count("SELECT count(*) FROM account.managed_account WHERE secret_path IS NOT NULL"));
            f.put("unverified", count("SELECT count(*) FROM account.managed_account WHERE secret_path IS NOT NULL AND rotation_status <> 'VERIFIED'"));
            f.put("findings", count("SELECT count(*) FROM account.account_finding WHERE resolved_at IS NULL"));
            f.put("emergencyPending", count("SELECT count(*) FROM account.credential_checkout WHERE emergency AND review_status = 'PENDING'"));
            f.put("reveals", count("SELECT coalesce(sum(reveal_count), 0) FROM account.credential_checkout WHERE started_at > now() - interval '7 days'"));
            f.put("failedOps", count("SELECT count(*) FROM operation.operation WHERE status IN ('FAILED','UNKNOWN','TIMEOUT','PARTIAL') "
                    + "AND created_at > now() - interval '7 days'"));
            f.put("pendingRequests", count("SELECT count(*) FROM request.access_request WHERE status = 'PENDING_APPROVAL'"));
            java.util.Set<java.util.UUID> to = new java.util.LinkedHashSet<>(roles.activeHolders("SECURITY_ADMINISTRATOR"));
            to.addAll(roles.activeHolders("PLATFORM_ADMINISTRATOR"));
            service.weeklyReport(to, f);
        }

        private String count(String sql) {
            try {
                return String.valueOf(jdbc.sql(sql).query(Long.class).single());
            } catch (RuntimeException e) {
                return "n/a";
            }
        }
    }

    @Bean
    NotificationListeners notificationListeners(NotificationService service, com.enterprise.iam.core.authorization.api.RoleDirectory roles) {
        return new NotificationListeners(service, roles);
    }

    /** Synchronous listeners: outbox rows commit or roll back with the originating change. */
    static class NotificationListeners {
        private final NotificationService service;
        private final com.enterprise.iam.core.authorization.api.RoleDirectory roles;

        NotificationListeners(NotificationService service, com.enterprise.iam.core.authorization.api.RoleDirectory roles) {
            this.service = service;
            this.roles = roles;
        }

        /** Break-glass: security administrators (and platform administrators) are told immediately. */
        @EventListener
        void onEmergency(com.enterprise.iam.core.account.api.EmergencyAccessUsed e) {
            java.util.Set<java.util.UUID> reviewers = new java.util.LinkedHashSet<>(roles.activeHolders("SECURITY_ADMINISTRATOR"));
            reviewers.addAll(roles.activeHolders("PLATFORM_ADMINISTRATOR"));
            service.onEmergency(e, reviewers);
        }

        @EventListener
        void onRoleAssignment(RoleAssignmentChanged e) {
            service.on(e);
        }

        @EventListener
        void onIdentityLifecycle(IdentityLifecycleChanged e) {
            service.on(e);
        }

        @EventListener
        void onAccessRequest(AccessRequestChanged e) {
            service.on(e);
        }
    }
}
