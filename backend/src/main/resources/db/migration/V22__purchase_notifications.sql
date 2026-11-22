-- Existing milestones predate the notification feature.
ALTER TABLE zonda_market.checkout_outbox
    ADD COLUMN notification_pending BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE zonda_market.checkout_outbox
    ALTER COLUMN notification_pending SET DEFAULT TRUE;

CREATE INDEX checkout_outbox_notification_pending_idx ON zonda_market.checkout_outbox(created_at, id)
    WHERE notification_pending AND event_type IN ('CHECKOUT_PAID', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED');

CREATE TABLE zonda_market.purchase_notification (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES zonda_market.checkout_outbox(id),
    recipient_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    type VARCHAR(32) NOT NULL CHECK (type IN ('PAYMENT_APPROVED', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED')),
    resource_id UUID NOT NULL,
    target_path VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    mail_recipient VARCHAR(254),
    mail_next_attempt_at TIMESTAMPTZ,
    mail_attempts INTEGER NOT NULL DEFAULT 0 CHECK (mail_attempts >= 0),
    mail_lease_id UUID,
    mail_lease_until TIMESTAMPTZ,
    mail_sent_at TIMESTAMPTZ,
    mail_provider_id TEXT,
    UNIQUE (event_id, recipient_id)
);

CREATE INDEX purchase_notification_history_idx
    ON zonda_market.purchase_notification(recipient_id, created_at DESC, id DESC);
CREATE INDEX purchase_notification_unread_idx
    ON zonda_market.purchase_notification(recipient_id) WHERE read_at IS NULL;
CREATE INDEX purchase_notification_mail_pending_idx
    ON zonda_market.purchase_notification(mail_next_attempt_at, id)
    WHERE mail_sent_at IS NULL AND mail_next_attempt_at IS NOT NULL;
