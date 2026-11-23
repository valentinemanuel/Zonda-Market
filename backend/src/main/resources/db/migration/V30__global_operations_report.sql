ALTER TABLE zonda_market.checkout_order_line
    ADD COLUMN category_id UUID REFERENCES zonda_market.category(id);

UPDATE zonda_market.checkout_order_line line
SET category_id = listing.category_id
FROM zonda_market.listing_sku sku
JOIN zonda_market.listing listing ON listing.id = sku.listing_id
WHERE sku.id = line.sku_id;

ALTER TABLE zonda_market.checkout_order_line ALTER COLUMN category_id SET NOT NULL;

CREATE INDEX checkout_outcome_approved_date_idx ON zonda_market.checkout_payment_outcome(created_at)
    WHERE result = 'APPROVED';
CREATE INDEX dispute_opened_at_idx ON zonda_market.dispute(opened_at);
