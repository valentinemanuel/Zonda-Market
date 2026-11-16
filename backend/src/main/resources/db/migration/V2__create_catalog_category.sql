CREATE TABLE zonda_market.category (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(120) NOT NULL UNIQUE,
    parent_id UUID REFERENCES zonda_market.category (id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT category_not_own_parent CHECK (parent_id <> id)
);

CREATE INDEX category_parent_idx ON zonda_market.category (parent_id);

INSERT INTO zonda_market.category (id, name, slug, created_at, updated_at)
VALUES ('e0020000-0000-4000-8000-000000000001', 'Tecnología', 'tecnologia', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
