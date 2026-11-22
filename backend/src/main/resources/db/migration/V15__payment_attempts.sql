CREATE TABLE zonda_market.payment_attempt (
    id UUID PRIMARY KEY,
    sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    method VARCHAR(16) NOT NULL CHECK (method IN ('CARD', 'CHECKOUT_PRO')),
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'CREADO', 'ENVIADO', 'PENDIENTE', 'APROBADO', 'RECHAZADO', 'RESULTADO_DESCONOCIDO')),
    request_key VARCHAR(128) NOT NULL,
    provider_key UUID NOT NULL UNIQUE,
    external_reference VARCHAR(128) NOT NULL UNIQUE,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'ARS'),
    expires_at TIMESTAMPTZ NOT NULL,
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (order_id, request_key)
);

CREATE UNIQUE INDEX payment_attempt_one_active
    ON zonda_market.payment_attempt (order_id)
    WHERE status IN ('CREADO', 'ENVIADO', 'PENDIENTE', 'RESULTADO_DESCONOCIDO');
CREATE UNIQUE INDEX payment_attempt_one_approved
    ON zonda_market.payment_attempt (order_id) WHERE status = 'APROBADO';
CREATE INDEX payment_attempt_history
    ON zonda_market.payment_attempt (order_id, sequence DESC);
