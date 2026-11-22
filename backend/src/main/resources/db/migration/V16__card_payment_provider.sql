ALTER TABLE zonda_market.payment_attempt
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'LOCAL'
        CHECK (provider IN ('LOCAL', 'MERCADO_PAGO')),
    ADD COLUMN card_request_key VARCHAR(128),
    ADD COLUMN provider_order_id VARCHAR(80),
    ADD CONSTRAINT payment_attempt_card_request_key UNIQUE (order_id, card_request_key),
    ADD CONSTRAINT payment_attempt_provider_order UNIQUE (provider, provider_order_id);
