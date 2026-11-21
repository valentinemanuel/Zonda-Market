CREATE INDEX checkout_order_buyer_history_idx
    ON zonda_market.checkout_order(buyer_id, created_at DESC, id DESC);
