CREATE TABLE zonda_market.payment_compensation (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    method VARCHAR(16) NOT NULL,
    provider_payment_id VARCHAR(80) NOT NULL,
    reason VARCHAR(16) NOT NULL CHECK (reason IN ('TARDIO', 'DUPLICADO')),
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDIENTE' CHECK (status IN ('PENDIENTE', 'CONFIRMADA', 'EN_REVISION')),
    provider_refund_id VARCHAR(80),
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    failure_code VARCHAR(48),
    UNIQUE (method, provider_payment_id),
    UNIQUE (method, provider_refund_id),
    FOREIGN KEY (method, provider_payment_id) REFERENCES zonda_market.payment_transaction(method, provider_payment_id),
    CHECK (status <> 'CONFIRMADA' OR provider_refund_id IS NOT NULL)
);
CREATE INDEX payment_compensation_order_idx ON zonda_market.payment_compensation(order_id, created_at, id);
CREATE INDEX payment_compensation_due_idx ON zonda_market.payment_compensation(next_attempt_at, id)
    WHERE status = 'PENDIENTE';

-- Enqueue historical approvals only when their compensation can be determined from persisted correlations.
INSERT INTO zonda_market.payment_compensation
    (id, order_id, method, provider_payment_id, reason, amount, trace_id, created_at, updated_at, next_attempt_at)
SELECT gen_random_uuid(), attempt.order_id, payment.method, payment.provider_payment_id,
       CASE WHEN outcome.result = 'APPROVED' THEN 'DUPLICADO' ELSE 'TARDIO' END,
       payment.amount, payment.trace_id, now(), now(), now()
FROM zonda_market.payment_transaction payment
JOIN zonda_market.payment_attempt attempt ON attempt.id = payment.attempt_id
JOIN zonda_market.checkout_order checkout ON checkout.id = attempt.order_id
LEFT JOIN zonda_market.checkout_payment_outcome outcome ON outcome.order_id = attempt.order_id
WHERE payment.status = 'APROBADO'
  AND (
      outcome.result IN ('LATE_APPROVAL', 'EXPIRED', 'REJECTED')
      OR (outcome.result = 'APPROVED' AND (
          (outcome.provider_payment_id IS NOT NULL AND (outcome.payment_method, outcome.provider_payment_id)
              <> (payment.method, payment.provider_payment_id))
          OR (outcome.provider_payment_id IS NULL AND (
              outcome.reference <> attempt.external_reference
              OR (attempt.provider_order_id IS NOT NULL AND attempt.provider_order_id <> payment.provider_order_id)
          ))
      ))
      OR (outcome.order_id IS NULL AND (checkout.status IN ('EXPIRADA', 'FALLIDA') OR attempt.status = 'RECHAZADO'))
  );
