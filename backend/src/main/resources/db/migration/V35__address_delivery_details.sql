ALTER TABLE zonda_market.account_address
    ADD COLUMN recipient VARCHAR(120),
    ADD COLUMN phone VARCHAR(32),
    ADD COLUMN delivery_details VARCHAR(250),
    ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE zonda_market.account_address
SET is_default = TRUE
WHERE id IN (
    SELECT DISTINCT ON (account_id) id
    FROM zonda_market.account_address
    WHERE NOT deleted
    ORDER BY account_id, created_at, id
);

CREATE UNIQUE INDEX uq_account_address_default ON zonda_market.account_address(account_id)
    WHERE is_default AND NOT deleted;
