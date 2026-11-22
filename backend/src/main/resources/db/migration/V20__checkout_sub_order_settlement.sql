CREATE TABLE zonda_market.checkout_sub_order_settlement (
    sub_order_id UUID PRIMARY KEY REFERENCES zonda_market.checkout_sub_order(id),
    merchandise NUMERIC(19,2) NOT NULL CHECK (merchandise > 0),
    shipping NUMERIC(19,2) NOT NULL CHECK (shipping >= 0),
    commission NUMERIC(19,2) NOT NULL CHECK (commission BETWEEN 0 AND merchandise),
    seller_net NUMERIC(19,2) GENERATED ALWAYS AS (merchandise + shipping - commission) STORED,
    currency VARCHAR(3) NOT NULL CHECK (currency = 'ARS'),
    recorded_at TIMESTAMPTZ NOT NULL
);

INSERT INTO zonda_market.checkout_sub_order_settlement
    (sub_order_id, merchandise, shipping, commission, currency, recorded_at)
SELECT sub.id, sub.merchandise, sub.shipping, sub.commission, ord.currency, outcome.created_at
FROM zonda_market.checkout_sub_order sub
JOIN zonda_market.checkout_order ord ON ord.id = sub.order_id
JOIN zonda_market.checkout_payment_outcome outcome ON outcome.order_id = sub.order_id
WHERE outcome.result = 'APPROVED';

CREATE VIEW zonda_market.payment_reconciliation_metrics AS
SELECT tx.method, (tx.verified_at AT TIME ZONE 'UTC')::date AS verified_day,
       count(*) FILTER (WHERE tx.status = 'APROBADO') AS approved,
       count(*) FILTER (WHERE tx.status = 'RECHAZADO') AS rejected,
       count(*) FILTER (WHERE tx.status = 'PENDIENTE') AS pending,
       avg(extract(epoch FROM tx.verified_at - attempt.created_at))
           FILTER (WHERE tx.status IN ('APROBADO', 'RECHAZADO')) AS avg_terminal_seconds
FROM zonda_market.payment_transaction tx
JOIN zonda_market.payment_attempt attempt ON attempt.id = tx.attempt_id
GROUP BY tx.method, (tx.verified_at AT TIME ZONE 'UTC')::date;
