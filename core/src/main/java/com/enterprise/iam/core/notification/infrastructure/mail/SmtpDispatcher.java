package com.enterprise.iam.core.notification.infrastructure.mail;

import com.enterprise.iam.core.operation.api.MessageDispatcher;
import com.enterprise.iam.core.operation.api.OutboxMessage;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Delivers {@code smtp} outbox messages. A notification already marked SENT is skipped, so at-least-once relay
 * delivery does not normally produce duplicate e-mails.
 */
public class SmtpDispatcher implements MessageDispatcher {

    private final JavaMailSender mail;
    private final JdbcClient jdbc;
    private final String from;

    public SmtpDispatcher(JavaMailSender mail, JdbcClient jdbc, String from) {
        this.mail = mail;
        this.jdbc = jdbc;
        this.from = from;
    }

    @Override
    public String kind() {
        return "smtp";
    }

    @Override
    public void dispatch(OutboxMessage m) {
        UUID id = UUID.fromString(String.valueOf(m.payload().get("notificationId")));
        String status = jdbc.sql("SELECT status FROM notification.notification WHERE id = :id").param("id", id)
                .query(String.class).optional().orElse("QUEUED");
        if ("SENT".equals(status)) {
            return;
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(String.valueOf(m.payload().get("to")));
        msg.setSubject(String.valueOf(m.payload().get("subject")));
        msg.setText(String.valueOf(m.payload().get("body")));
        mail.send(msg);
        jdbc.sql("UPDATE notification.notification SET status = 'SENT', sent_at = now() WHERE id = :id").param("id", id).update();
    }
}
