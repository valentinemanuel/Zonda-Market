CREATE TABLE zonda_market.cart_reorder (
    cart_id UUID NOT NULL REFERENCES zonda_market.cart(id),
    request_key UUID NOT NULL,
    order_id UUID NOT NULL REFERENCES zonda_market.checkout_order(id),
    PRIMARY KEY (cart_id, request_key)
);
