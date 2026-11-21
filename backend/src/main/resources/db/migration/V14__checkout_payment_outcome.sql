CREATE TABLE zonda_market.checkout_payment_outcome (
    order_id UUID PRIMARY KEY REFERENCES zonda_market.checkout_order(id),
    reference VARCHAR(128) NOT NULL UNIQUE,
    outcome VARCHAR(12) NOT NULL CHECK (outcome IN ('APPROVED', 'REJECTED')),
    result VARCHAR(20) NOT NULL CHECK (result IN ('APPROVED', 'REJECTED', 'LATE_APPROVAL', 'EXPIRED')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'ARS'),
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
