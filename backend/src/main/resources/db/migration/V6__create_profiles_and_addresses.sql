ALTER TABLE zonda_market.user_account ADD COLUMN display_name VARCHAR(80);

CREATE TABLE zonda_market.account_address (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    street VARCHAR(140) NOT NULL,
    city VARCHAR(80) NOT NULL,
    province_code VARCHAR(4) NOT NULL CHECK (province_code ~ '^AR-[ABCDEFGHJKLMNPQRSTUVWXYZ]$'),
    postal_code VARCHAR(8) NOT NULL CHECK (postal_code ~ '^([0-9]{4}|[A-Z][0-9]{4}[A-Z]{3})$'),
    country VARCHAR(2) NOT NULL CHECK (country = 'AR'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);

CREATE INDEX ix_account_address_active ON zonda_market.account_address(account_id, created_at, id)
    WHERE NOT deleted;

CREATE TABLE zonda_market.seller_profile (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL CONSTRAINT uq_seller_profile_account UNIQUE REFERENCES zonda_market.user_account(id),
    type VARCHAR(12) NOT NULL CHECK (type IN ('PERSONAL', 'COMERCIO')),
    display_name VARCHAR(80) NOT NULL,
    slug VARCHAR(50) NOT NULL CONSTRAINT uq_seller_profile_slug UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    bio VARCHAR(500) NOT NULL,
    activation_trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);
