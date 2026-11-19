ALTER TABLE zonda_market.category
    ADD COLUMN attribute_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT category_attribute_schema_object CHECK (jsonb_typeof(attribute_schema) = 'object');

INSERT INTO zonda_market.category (id, name, slug, attribute_schema, created_at, updated_at)
VALUES ('e2010000-0000-4000-8000-000000000001', 'Indumentaria', 'indumentaria',
        '{"color":["Negro","Blanco","Azul","Rojo"],"talle":["XS","S","M","L","XL"]}',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE zonda_market.listing (
    id UUID PRIMARY KEY,
    seller_id UUID NOT NULL REFERENCES zonda_market.seller_profile (id),
    category_id UUID NOT NULL REFERENCES zonda_market.category (id),
    title VARCHAR(160) NOT NULL,
    description VARCHAR(5000) NOT NULL,
    specs JSONB NOT NULL CHECK (jsonb_typeof(specs) = 'object'),
    status VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX listing_seller_idx ON zonda_market.listing (seller_id, created_at, id) WHERE NOT deleted;
CREATE INDEX listing_category_idx ON zonda_market.listing (category_id);

CREATE TABLE zonda_market.listing_sku (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES zonda_market.listing (id),
    code VARCHAR(64) NOT NULL,
    attributes JSONB NOT NULL CHECK (jsonb_typeof(attributes) = 'object'),
    price NUMERIC(19,2) NOT NULL CHECK (price > 0),
    available_stock INTEGER NOT NULL CHECK (available_stock >= 0),
    reserved_stock INTEGER NOT NULL DEFAULT 0 CHECK (reserved_stock >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT listing_sku_code_unique UNIQUE (code),
    CONSTRAINT listing_sku_combination_unique UNIQUE (listing_id, attributes)
);
