-- V8 — Notification history (spec §57). Delivery itself goes through operation.outbox_message.

CREATE TABLE notification.notification (
    id           uuid        PRIMARY KEY,
    channel      text        NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK')),
    recipient    text        NOT NULL,
    template_key text        NOT NULL,
    subject      text,
    status       text        NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'FAILED')),
    created_at   timestamptz NOT NULL,
    sent_at      timestamptz
);
CREATE INDEX ix_notification_status ON notification.notification (status, created_at);
