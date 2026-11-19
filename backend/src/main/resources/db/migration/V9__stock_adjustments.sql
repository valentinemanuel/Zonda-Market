CREATE TABLE zonda_market.stock_movement (
    id UUID PRIMARY KEY,
    sku_id UUID NOT NULL REFERENCES zonda_market.listing_sku (id),
    actor_id UUID NOT NULL REFERENCES zonda_market.user_account (id),
    trace_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    delta INTEGER NOT NULL CHECK (delta <> 0),
    reason VARCHAR(240) NOT NULL CHECK (length(btrim(reason)) > 0),
    before_stock INTEGER NOT NULL CHECK (before_stock >= 0),
    after_stock INTEGER NOT NULL CHECK (after_stock >= 0),
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    resulting_version BIGINT NOT NULL CHECK (resulting_version > expected_version),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT stock_movement_consistent_balance CHECK (before_stock::BIGINT + delta = after_stock),
    CONSTRAINT stock_movement_idempotency UNIQUE (sku_id, idempotency_key),
    CONSTRAINT stock_movement_sku_version UNIQUE (sku_id, resulting_version)
);
