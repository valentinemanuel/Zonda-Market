ALTER TABLE zonda_market.user_account
    ADD COLUMN suspended BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE zonda_market.listing ADD COLUMN moderation_hidden BOOLEAN NOT NULL DEFAULT FALSE;

CREATE OR REPLACE VIEW zonda_market.public_listing AS
SELECT l.id, l.slug, l.title, l.category_id, l.search_document, seller.account_id AS seller_account_id,
       seller.slug AS seller_slug, seller.display_name AS seller_name
FROM zonda_market.listing l
JOIN zonda_market.seller_profile seller ON seller.id = l.seller_id AND NOT seller.deleted
JOIN zonda_market.user_account account ON account.id = seller.account_id AND NOT account.deleted
    AND NOT account.suspended AND account.role = 'SELLER'
JOIN zonda_market.category category ON category.id = l.category_id AND NOT category.deleted
WHERE l.status = 'ACTIVA' AND NOT l.deleted AND NOT l.moderation_hidden
  AND NOT EXISTS (SELECT 1 FROM zonda_market.category child WHERE child.parent_id = category.id AND NOT child.deleted)
  AND EXISTS (SELECT 1 FROM zonda_market.listing_image image WHERE image.listing_id = l.id AND NOT image.deleted)
  AND EXISTS (SELECT 1 FROM zonda_market.listing_sku sku WHERE sku.listing_id = l.id AND NOT sku.deleted);

CREATE TABLE zonda_market.moderation_audit (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    listing_id UUID REFERENCES zonda_market.listing(id),
    account_id UUID REFERENCES zonda_market.user_account(id),
    action VARCHAR(12) NOT NULL,
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    before_state VARCHAR(12) NOT NULL,
    after_state VARCHAR(12) NOT NULL,
    target_version BIGINT NOT NULL CHECK (target_version > 0),
    trace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((listing_id IS NOT NULL AND account_id IS NULL AND action IN ('HIDE', 'RESTORE'))
        OR (account_id IS NOT NULL AND listing_id IS NULL AND action IN ('SUSPEND', 'REACTIVATE')))
);

CREATE UNIQUE INDEX moderation_audit_listing_idx
    ON zonda_market.moderation_audit(listing_id, target_version DESC) WHERE listing_id IS NOT NULL;
CREATE UNIQUE INDEX moderation_audit_account_idx
    ON zonda_market.moderation_audit(account_id, target_version DESC) WHERE account_id IS NOT NULL;
