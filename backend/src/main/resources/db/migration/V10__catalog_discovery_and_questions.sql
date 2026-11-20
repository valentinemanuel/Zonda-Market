ALTER TABLE zonda_market.listing ADD COLUMN search_document TSVECTOR GENERATED ALWAYS AS (
    setweight(to_tsvector('spanish', title), 'A') ||
    setweight(to_tsvector('spanish', description), 'B')
) STORED;

CREATE INDEX listing_search_gin ON zonda_market.listing USING GIN (search_document)
    WHERE status = 'ACTIVA' AND NOT deleted;

CREATE VIEW zonda_market.public_listing AS
SELECT l.id, l.slug, l.title, l.category_id, l.search_document, seller.account_id AS seller_account_id,
       seller.slug AS seller_slug, seller.display_name AS seller_name
FROM zonda_market.listing l
JOIN zonda_market.seller_profile seller ON seller.id = l.seller_id AND NOT seller.deleted
JOIN zonda_market.user_account account ON account.id = seller.account_id AND NOT account.deleted AND account.role = 'SELLER'
JOIN zonda_market.category category ON category.id = l.category_id AND NOT category.deleted
WHERE l.status = 'ACTIVA' AND NOT l.deleted
  AND NOT EXISTS (SELECT 1 FROM zonda_market.category child WHERE child.parent_id = category.id AND NOT child.deleted)
  AND EXISTS (SELECT 1 FROM zonda_market.listing_image image WHERE image.listing_id = l.id AND NOT image.deleted)
  AND EXISTS (SELECT 1 FROM zonda_market.listing_sku sku WHERE sku.listing_id = l.id AND NOT sku.deleted);

CREATE TABLE zonda_market.listing_question (
    id UUID PRIMARY KEY,
    listing_id UUID NOT NULL REFERENCES zonda_market.listing(id),
    author_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    question_text VARCHAR(500) NOT NULL CHECK (length(btrim(question_text)) > 0),
    answer_text VARCHAR(1000) CHECK (length(btrim(answer_text)) > 0),
    answered_at TIMESTAMPTZ,
    answered_by UUID REFERENCES zonda_market.user_account(id),
    question_trace_id UUID NOT NULL,
    answer_trace_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK ((answer_text IS NULL AND answered_at IS NULL AND answered_by IS NULL AND answer_trace_id IS NULL)
        OR (answer_text IS NOT NULL AND answered_at IS NOT NULL AND answered_by IS NOT NULL AND answer_trace_id IS NOT NULL))
);

CREATE INDEX listing_question_public_idx ON zonda_market.listing_question (listing_id, created_at DESC, id)
    WHERE NOT deleted;
CREATE INDEX listing_question_pending_idx ON zonda_market.listing_question (listing_id, created_at, id)
    WHERE NOT deleted AND answered_at IS NULL;

CREATE TABLE zonda_market.listing_question_rate (
    account_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    action VARCHAR(8) NOT NULL CHECK (action IN ('QUESTION', 'ANSWER')),
    window_start TIMESTAMPTZ NOT NULL,
    used INTEGER NOT NULL CHECK (used > 0),
    PRIMARY KEY (account_id, action)
);
