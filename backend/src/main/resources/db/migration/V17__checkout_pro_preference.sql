ALTER TABLE zonda_market.payment_attempt
    ADD COLUMN pro_request_key VARCHAR(128),
    ADD COLUMN preference_id VARCHAR(80),
    ADD COLUMN checkout_url VARCHAR(2048),
    ADD CONSTRAINT payment_attempt_pro_request_key UNIQUE (order_id, pro_request_key),
    ADD CONSTRAINT payment_attempt_preference UNIQUE (preference_id),
    ADD CONSTRAINT payment_attempt_preference_url CHECK ((preference_id IS NULL) = (checkout_url IS NULL));
