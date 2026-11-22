ALTER TABLE zonda_market.payment_attempt
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'LOCAL'
        CHECK (provider IN ('LOCAL', 'MERCADO_PAGO')),
    ADD COLUMN card_request_key VARCHAR(128),
    ADD COLUMN provider_payment_id BIGINT CHECK (provider_payment_id > 0),
    ADD CONSTRAINT payment_attempt_card_request_key UNIQUE (order_id, card_request_key),
    ADD CONSTRAINT payment_attempt_provider_payment UNIQUE (provider, provider_payment_id);
