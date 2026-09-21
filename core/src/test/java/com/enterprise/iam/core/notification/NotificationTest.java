package com.enterprise.iam.core.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.authorization.api.RoleAssignmentChanged;
import com.enterprise.iam.core.identity.api.IdentityLifecycleChanged;
import com.enterprise.iam.core.identity.api.IdentitySummary;
import com.enterprise.iam.core.notification.application.NotificationService;
import com.enterprise.iam.core.notification.domain.NotificationTemplates;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTest {

    record Sent(String destination, Map<String, Object> payload) {
    }

    private final UUID identityId = UUID.randomUUID();
    private final List<Sent> outbox = new ArrayList<>();

    private NotificationService service(boolean emailEnabled) {
        return new NotificationService(id -> Optional.of(new IdentitySummary(identityId, UUID.randomUUID(), "jdoe", "Jane Doe",
                "jane@example.org", "EMPLOYEE", "ACTIVE", null, null, null)),
                (dest, type, id, payload, headers) -> {
                    outbox.add(new Sent(dest, payload));
                    return UUID.randomUUID();
                }, (id, ch, to, tpl, subj) -> { }, Clock.systemUTC(), emailEnabled);
    }

    @Test
    void subjectCannotInjectMailHeaders() {
        NotificationTemplates.Rendered r = NotificationTemplates.render("role-assignment.granted",
                Map.of("role", "X\r\nBcc: attacker@example.org", "displayName", "A", "username", "a", "reference", "r"));
        assertFalse(r.subject().contains("\n"));
        assertFalse(r.subject().contains("\r"));
        assertTrue(r.body().contains("Hello A"));
    }

    @Test
    void roleChangesProduceEmailThroughTheOutbox() {
        service(true).on(new RoleAssignmentChanged(UUID.randomUUID(), identityId, "AUDITOR", "GRANTED", UUID.randomUUID()));
        assertEquals(1, outbox.size());
        assertEquals("smtp", outbox.get(0).destination());
        assertEquals("jane@example.org", outbox.get(0).payload().get("to"));
        assertTrue(String.valueOf(outbox.get(0).payload().get("subject")).contains("AUDITOR"));
    }

    @Test
    void lifecycleChangesAlwaysFeedIntegrationsEvenWithoutEmail() {
        service(false).on(new IdentityLifecycleChanged(identityId, "ACTIVE", "SUSPENDED", "investigation", UUID.randomUUID()));
        assertEquals(1, outbox.size(), "no e-mail when SMTP is not configured — but the integration event is still published");
        assertEquals("amqp:iam.events/identity.lifecycle", outbox.get(0).destination());
        assertEquals("SUSPENDED", outbox.get(0).payload().get("to"));
    }
}
