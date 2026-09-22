package com.enterprise.iam.core.notification.application;

import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.identity.api.IdentityDirectory;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.notification.domain.NotificationTemplates;
import com.enterprise.iam.core.operation.api.OutboxPublisher;
import com.enterprise.iam.core.request.api.AccessRequestChanged;
import com.enterprise.iam.kernel.Ids;
import java.time.Clock;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns domain events into e-mail notifications written to the outbox in the same transaction (spec §57, RES2).
 * Also publishes identity lifecycle events to {@code iam.events} for integrations (HR/SIEM/ITSM consumers, Phase 8).
 * When e-mail is not configured, notifications are skipped (and health reports SMTP as not configured) — never faked.
 */
public class NotificationService {

    /** Records the notification history row. */
    public interface NotificationLog {
        void queued(UUID id, String channel, String recipient, String templateKey, String subject);
    }

    private final IdentityDirectory identities;
    private final OutboxPublisher outbox;
    private final NotificationLog log;
    private final Clock clock;
    private final boolean emailEnabled;

    public NotificationService(IdentityDirectory identities, OutboxPublisher outbox, NotificationLog log, Clock clock, boolean emailEnabled) {
        this.identities = identities;
        this.outbox = outbox;
        this.log = log;
        this.clock = clock;
        this.emailEnabled = emailEnabled;
    }

    public void on(RoleAssignmentChanged e) {
        String template = "role-assignment." + e.change().toLowerCase(Locale.ROOT);
        if (!NotificationTemplates.exists(template)) {
            return;
        }
        Map<String, String> v = new HashMap<>();
        v.put("role", e.roleCode());
        v.put("reference", e.assignmentId().toString());
        email(e.identityId(), template, v);
    }

    public void on(AccessRequestChanged e) {
        boolean pending = "PENDING_APPROVAL".equals(e.status());
        for (UUID who : e.recipients()) {
            Map<String, String> v = new HashMap<>();
            v.put("role", e.roleCode());
            v.put("requester", e.requesterName() == null ? "A user" : e.requesterName());
            v.put("status", e.status());
            v.put("reason", e.reason() == null ? "" : e.reason());
            v.put("reference", e.requestId().toString());
            email(who, pending ? "access-request.pending" : "access-request.decided", v);
        }
    }

    public void on(IdentityLifecycleChanged e) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "identity.lifecycle.changed");
        event.put("identityId", e.identityId().toString());
        event.put("from", e.fromState());
        event.put("to", e.toState());
        event.put("actorIdentityId", String.valueOf(e.actorIdentityId()));
        event.put("occurredAt", clock.instant().toString());
        outbox.enqueue("amqp:iam.events/identity.lifecycle", "identity", e.identityId().toString(), event, Map.of());

        Map<String, String> v = new HashMap<>();
        v.put("from", e.fromState());
        v.put("state", e.toState());
        v.put("reason", e.reason() == null ? "-" : e.reason());
        v.put("reference", e.identityId().toString());
        email(e.identityId(), "identity.lifecycle", v);
    }

    private void email(UUID identityId, String template, Map<String, String> values) {
        if (!emailEnabled) {
            return;
        }
        Optional<IdentitySummary> who = identities.find(identityId);
        if (who.isEmpty() || who.get().email() == null || who.get().email().isBlank()) {
            return;
        }
        Map<String, String> v = new HashMap<>(values);
        v.put("displayName", who.get().displayName());
        v.put("username", who.get().username());
        NotificationTemplates.Rendered r = NotificationTemplates.render(template, v);
        UUID id = Ids.newId(clock);
        log.queued(id, "EMAIL", who.get().email(), template, r.subject());
        Map<String, Object> payload = new HashMap<>();
        payload.put("notificationId", id.toString());
        payload.put("to", who.get().email());
        payload.put("subject", r.subject());
        payload.put("body", r.body());
        outbox.enqueue("smtp", "notification", id.toString(), payload, Map.of());
    }
}
