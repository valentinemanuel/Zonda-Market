CREATE TABLE zonda_market.seller_review (
    id UUID PRIMARY KEY,
    sub_order_id UUID NOT NULL UNIQUE REFERENCES zonda_market.checkout_sub_order(id),
    author_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(1000),
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    trace_id UUID NOT NULL
);
