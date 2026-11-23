ALTER TABLE zonda_market.dispute
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0);

CREATE TABLE zonda_market.dispute_resolution (
    id UUID PRIMARY KEY,
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    dispute_id UUID NOT NULL REFERENCES zonda_market.dispute(id),
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    sub_order_id UUID NOT NULL REFERENCES zonda_market.checkout_sub_order(id),
    actor_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    request_key UUID NOT NULL,
    expected_version INTEGER NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    outcome VARCHAR(20) NOT NULL CHECK (outcome IN ('SIN_REEMBOLSO', 'REEMBOLSO')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('CERRADA', 'PENDIENTE', 'CONFIRMADA', 'RECHAZADA', 'EN_REVISION')),
    amount NUMERIC(19,2),
    method VARCHAR(16),
    payment_id VARCHAR(80),
    provider_order_id VARCHAR(80),
    payment_reference VARCHAR(128),
    captured NUMERIC(19,2),
    provider_refund_id VARCHAR(80),
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    UNIQUE (dispute_id, request_key),
    UNIQUE (method, payment_id, provider_refund_id),
    FOREIGN KEY (method, payment_id) REFERENCES zonda_market.payment_transaction(method, provider_payment_id) MATCH FULL,
    CHECK ((outcome = 'SIN_REEMBOLSO' AND amount IS NULL AND status = 'CERRADA')
        OR (outcome = 'REEMBOLSO' AND amount IS NOT NULL AND captured IS NOT NULL
            AND amount > 0 AND captured >= amount AND status <> 'CERRADA')),
    CHECK (status <> 'CONFIRMADA' OR provider_refund_id IS NOT NULL)
);

CREATE INDEX dispute_resolution_history_idx ON zonda_market.dispute_resolution(dispute_id, sequence DESC);
CREATE INDEX dispute_resolution_order_idx ON zonda_market.dispute_resolution(order_id, sequence);
CREATE INDEX dispute_resolution_due_idx ON zonda_market.dispute_resolution(next_attempt_at, sequence)
    WHERE status IN ('PENDIENTE', 'EN_REVISION');
CREATE UNIQUE INDEX dispute_resolution_active_idx ON zonda_market.dispute_resolution(dispute_id)
    WHERE status IN ('PENDIENTE', 'EN_REVISION');

DROP INDEX zonda_market.checkout_outbox_notification_pending_idx;
CREATE INDEX checkout_outbox_notification_pending_idx ON zonda_market.checkout_outbox(created_at, id)
    WHERE notification_pending AND event_type IN
        ('CHECKOUT_PAID', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED', 'DISPUTE_OPENED', 'DISPUTE_ASSIGNED', 'DISPUTE_RESOLVED');
ALTER TABLE zonda_market.purchase_notification DROP CONSTRAINT purchase_notification_type_check;
ALTER TABLE zonda_market.purchase_notification ADD CONSTRAINT purchase_notification_type_check
    CHECK (type IN ('PAYMENT_APPROVED', 'SUB_ORDER_SHIPPED', 'DELIVERY_CONFIRMED',
        'DISPUTE_OPENED', 'DISPUTE_ASSIGNED', 'DISPUTE_RESOLVED'));
