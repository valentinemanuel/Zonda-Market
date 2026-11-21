ALTER TABLE zonda_market.category
    ADD COLUMN commission_bps INTEGER NOT NULL DEFAULT 0 CHECK (commission_bps BETWEEN 0 AND 10000);
ALTER TABLE zonda_market.seller_profile
    ADD COLUMN shipping_cost NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (shipping_cost >= 0);

CREATE TABLE zonda_market.checkout_quote (
    id UUID PRIMARY KEY,
    cart_id UUID NOT NULL REFERENCES zonda_market.cart(id),
    cart_version BIGINT NOT NULL CHECK (cart_version >= 0),
    address_id UUID NOT NULL REFERENCES zonda_market.account_address(id),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at)
);
CREATE INDEX checkout_quote_expiry_idx ON zonda_market.checkout_quote(expires_at);

CREATE TABLE zonda_market.checkout_order (
    id UUID PRIMARY KEY,
    buyer_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    cart_id UUID NOT NULL REFERENCES zonda_market.cart(id),
    quote_id UUID NOT NULL UNIQUE REFERENCES zonda_market.checkout_quote(id),
    status VARCHAR(24) NOT NULL,
    total NUMERIC(19,2) NOT NULL CHECK (total > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'ARS'),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    UNIQUE (buyer_id, idempotency_key)
);
CREATE UNIQUE INDEX checkout_order_active_buyer_idx ON zonda_market.checkout_order(buyer_id)
    WHERE status IN ('RESERVADA', 'PAGO_PENDIENTE');
CREATE INDEX checkout_order_active_expiry_idx ON zonda_market.checkout_order(expires_at, id)
    WHERE status IN ('RESERVADA', 'PAGO_PENDIENTE');

CREATE TABLE zonda_market.checkout_sub_order (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    seller_id UUID NOT NULL REFERENCES zonda_market.seller_profile(id),
    seller_name VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    address_snapshot JSONB NOT NULL CHECK (jsonb_typeof(address_snapshot) = 'object'),
    merchandise NUMERIC(19,2) NOT NULL CHECK (merchandise > 0),
    shipping NUMERIC(19,2) NOT NULL CHECK (shipping >= 0),
    commission NUMERIC(19,2) NOT NULL CHECK (commission BETWEEN 0 AND merchandise),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (order_id, seller_id)
);
CREATE TABLE zonda_market.checkout_order_line (
    id UUID PRIMARY KEY,
    sub_order_id UUID NOT NULL REFERENCES zonda_market.checkout_sub_order(id),
    sku_id UUID NOT NULL REFERENCES zonda_market.listing_sku(id),
    sku_code VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    attributes JSONB NOT NULL CHECK (jsonb_typeof(attributes) = 'object'),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price > 0),
    commission_bps INTEGER NOT NULL CHECK (commission_bps BETWEEN 0 AND 10000),
    commission NUMERIC(19,2) NOT NULL CHECK (commission BETWEEN 0 AND unit_price * quantity),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (sub_order_id, sku_id)
);

CREATE TABLE zonda_market.stock_reservation (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    sku_id UUID NOT NULL REFERENCES zonda_market.listing_sku(id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    status VARCHAR(12) NOT NULL CHECK (status IN ('ACTIVA', 'LIBERADA', 'CONSUMIDA')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    UNIQUE (order_id, sku_id)
);
CREATE INDEX stock_reservation_active_sku_idx ON zonda_market.stock_reservation(sku_id, expires_at)
    WHERE status = 'ACTIVA';

CREATE TABLE zonda_market.checkout_outbox (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    event_type VARCHAR(40) NOT NULL,
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    UNIQUE (order_id, event_type)
);
