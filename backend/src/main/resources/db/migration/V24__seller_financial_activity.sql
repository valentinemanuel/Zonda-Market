CREATE INDEX checkout_settlement_recorded_idx
    ON zonda_market.checkout_sub_order_settlement(recorded_at, sub_order_id);

-- One confirmed post-sale adjustment per refund. Payment compensation has its own ledger.
CREATE TABLE zonda_market.checkout_sub_order_refund (
    refund_id UUID PRIMARY KEY,
    sub_order_id UUID NOT NULL REFERENCES zonda_market.checkout_sub_order_settlement(sub_order_id),
    merchandise_refunded NUMERIC(19,2) NOT NULL CHECK (merchandise_refunded >= 0),
    shipping_refunded NUMERIC(19,2) NOT NULL CHECK (shipping_refunded >= 0),
    commission_reversed NUMERIC(19,2) NOT NULL CHECK (commission_reversed BETWEEN 0 AND merchandise_refunded),
    confirmed_at TIMESTAMPTZ NOT NULL,
    trace_id UUID NOT NULL,
    CHECK (merchandise_refunded + shipping_refunded > 0)
);

CREATE INDEX checkout_sub_order_refund_confirmed_idx
    ON zonda_market.checkout_sub_order_refund(confirmed_at, sub_order_id);
CREATE INDEX checkout_sub_order_refund_sub_order_idx
    ON zonda_market.checkout_sub_order_refund(sub_order_id);
