CREATE TABLE zonda_market.dispute (
    id UUID PRIMARY KEY,
    sub_order_id UUID NOT NULL UNIQUE REFERENCES zonda_market.checkout_sub_order(id),
    status VARCHAR(28) NOT NULL CHECK (status IN ('ABIERTA', 'EN_MEDIACION', 'REEMBOLSO_PENDIENTE',
        'CERRADA_SIN_REEMBOLSO', 'CERRADA_REEMBOLSADA')),
    reason VARCHAR(120) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    assigned_by UUID REFERENCES zonda_market.user_account(id),
    assigned_at TIMESTAMPTZ,
    CHECK ((status = 'ABIERTA' AND assigned_by IS NULL AND assigned_at IS NULL)
        OR (status <> 'ABIERTA' AND assigned_by IS NOT NULL AND assigned_at IS NOT NULL))
);

CREATE INDEX dispute_queue_idx ON zonda_market.dispute(status, opened_at, id);

CREATE TABLE zonda_market.dispute_message (
    id UUID PRIMARY KEY,
    dispute_id UUID NOT NULL REFERENCES zonda_market.dispute(id),
    author_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    author_role VARCHAR(16) NOT NULL CHECK (author_role IN ('BUYER', 'SELLER', 'ADMIN')),
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    request_key UUID,
    UNIQUE (dispute_id, author_id, request_key)
);

CREATE INDEX dispute_message_history_idx ON zonda_market.dispute_message(dispute_id, created_at DESC, id DESC);

DROP INDEX zonda_market.checkout_outbox_notification_pending_idx;
CREATE INDEX checkout_outbox_notification_pending_idx ON zonda_market.checkout_outbox(created_at, id)
    WHERE notification_pending AND event_type IN
        ('CHECKOUT_PAID', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED', 'DISPUTE_OPENED', 'DISPUTE_ASSIGNED');

ALTER TABLE zonda_market.purchase_notification DROP CONSTRAINT purchase_notification_type_check;
ALTER TABLE zonda_market.purchase_notification ADD CONSTRAINT purchase_notification_type_check
    CHECK (type IN ('PAYMENT_APPROVED', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED', 'DISPUTE_OPENED', 'DISPUTE_ASSIGNED'));
