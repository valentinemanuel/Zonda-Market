ALTER TABLE zonda_market.listing
    ADD COLUMN slug VARCHAR(200) UNIQUE,
    ADD CONSTRAINT listing_publication_state CHECK (
        (status = 'BORRADOR' AND slug IS NULL)
        OR (status IN ('ACTIVA', 'ARCHIVADA') AND slug IS NOT NULL)
    );

CREATE TABLE zonda_market.listing_image (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES zonda_market.listing (id),
    provider VARCHAR(20) NOT NULL,
    public_id UUID NOT NULL UNIQUE,
    url VARCHAR(400) NOT NULL,
    thumbnail_url VARCHAR(400) NOT NULL,
    alt VARCHAR(160) NOT NULL DEFAULT '',
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 5),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX listing_images_idx ON zonda_market.listing_image (listing_id, position, id) WHERE NOT deleted;

CREATE TABLE zonda_market.listing_status_change (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES zonda_market.listing (id),
    actor_id UUID NOT NULL REFERENCES zonda_market.user_account (id),
    trace_id UUID NOT NULL,
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE zonda_market.listing_media_cleanup (
    public_id UUID PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ
);

CREATE INDEX listing_media_cleanup_pending_idx
    ON zonda_market.listing_media_cleanup (provider, next_attempt_at, public_id)
    WHERE completed_at IS NULL AND cancelled_at IS NULL;

CREATE TABLE zonda_market.local_listing_media (
    public_id UUID PRIMARY KEY,
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    payload BYTEA NOT NULL CHECK (octet_length(payload) BETWEEN 1 AND 5242880)
);
