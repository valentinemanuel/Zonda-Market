ALTER TABLE zonda_market.checkout_order
    ADD COLUMN direct_sku_id UUID REFERENCES zonda_market.listing_sku(id);

ALTER TABLE zonda_market.payment_attempt
    ADD COLUMN card_last_four VARCHAR(4) CHECK (card_last_four ~ '^[0-9]{4}$');
