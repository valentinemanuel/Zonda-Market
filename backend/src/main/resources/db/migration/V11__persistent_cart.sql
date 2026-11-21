CREATE TABLE zonda_market.cart (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL UNIQUE REFERENCES zonda_market.user_account (id),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE zonda_market.cart_item (
    id UUID PRIMARY KEY,
    cart_id UUID NOT NULL REFERENCES zonda_market.cart (id),
    sku_id UUID NOT NULL REFERENCES zonda_market.listing_sku (id),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    reference_price NUMERIC(19,2) NOT NULL CHECK (reference_price > 0),
    reference_title VARCHAR(160) NOT NULL,
    reference_attributes JSONB NOT NULL CHECK (jsonb_typeof(reference_attributes) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (cart_id, sku_id)
);
