CREATE TABLE zonda_market.payment_webhook_inbox (
    id UUID PRIMARY KEY,
    topic VARCHAR(8) NOT NULL CHECK (topic IN ('PAYMENT', 'ORDER')),
    notification_id VARCHAR(80) NOT NULL,
    resource_id VARCHAR(80) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(48),
    UNIQUE (topic, resource_id, notification_id),
    UNIQUE (topic, request_id)
);
CREATE INDEX payment_webhook_due_idx
    ON zonda_market.payment_webhook_inbox (next_attempt_at, id) WHERE finished_at IS NULL;

CREATE TABLE zonda_market.payment_transaction (
    method VARCHAR(16) NOT NULL CHECK (method IN ('CARD', 'CHECKOUT_PRO')),
    provider_payment_id VARCHAR(80) NOT NULL,
    provider_order_id VARCHAR(80) NOT NULL,
    attempt_id UUID NOT NULL REFERENCES zonda_market.payment_attempt(id),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDIENTE', 'APROBADO', 'RECHAZADO')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'ARS'),
    trace_id UUID NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (method, provider_payment_id)
);
CREATE INDEX payment_transaction_attempt_idx ON zonda_market.payment_transaction (attempt_id);

ALTER TABLE zonda_market.checkout_payment_outcome
    ADD COLUMN payment_method VARCHAR(16),
    ADD COLUMN provider_payment_id VARCHAR(80),
    ADD CONSTRAINT checkout_outcome_provider_payment FOREIGN KEY (payment_method, provider_payment_id)
        REFERENCES zonda_market.payment_transaction (method, provider_payment_id) MATCH FULL;

ALTER TABLE zonda_market.payment_attempt ADD COLUMN reconcile_after TIMESTAMPTZ;
CREATE INDEX payment_attempt_reconciliation_idx
    ON zonda_market.payment_attempt (method, COALESCE(reconcile_after, updated_at), id)
    WHERE provider = 'MERCADO_PAGO' AND status IN ('ENVIADO', 'PENDIENTE', 'RESULTADO_DESCONOCIDO');
