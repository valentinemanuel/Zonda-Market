ALTER TABLE zonda_market.checkout_sub_order
    ADD COLUMN fulfillment_version INTEGER NOT NULL DEFAULT 0 CHECK (fulfillment_version >= 0),
    ADD COLUMN carrier VARCHAR(24),
    ADD COLUMN tracking_code VARCHAR(64),
    ADD COLUMN shipped_at TIMESTAMPTZ,
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD CONSTRAINT checkout_sub_order_shipment_check CHECK (
        (carrier IS NULL AND tracking_code IS NULL AND shipped_at IS NULL)
        OR (carrier IS NOT NULL AND tracking_code IS NOT NULL AND shipped_at IS NOT NULL)
    ),
    ADD CONSTRAINT checkout_sub_order_delivery_check CHECK (
        delivered_at IS NULL OR (shipped_at IS NOT NULL AND delivered_at >= shipped_at)
    ),
    ADD CONSTRAINT checkout_sub_order_fulfillment_state_check CHECK (
        (status <> 'DESPACHADA' OR (shipped_at IS NOT NULL AND delivered_at IS NULL))
        AND (status <> 'ENTREGADA' OR delivered_at IS NOT NULL)
    );

CREATE INDEX checkout_sub_order_seller_history_idx
    ON zonda_market.checkout_sub_order(seller_id, created_at DESC, id DESC);

ALTER TABLE zonda_market.checkout_outbox
    ADD COLUMN sub_order_id UUID REFERENCES zonda_market.checkout_sub_order(id),
    ADD COLUMN actor_id UUID REFERENCES zonda_market.user_account(id),
    ADD CONSTRAINT checkout_outbox_sub_order_actor_check CHECK (sub_order_id IS NULL OR actor_id IS NOT NULL),
    DROP CONSTRAINT checkout_outbox_order_id_event_type_key;

CREATE UNIQUE INDEX checkout_outbox_order_event_idx
    ON zonda_market.checkout_outbox(order_id, event_type) WHERE sub_order_id IS NULL;
CREATE UNIQUE INDEX checkout_outbox_sub_order_event_idx
    ON zonda_market.checkout_outbox(sub_order_id, event_type) WHERE sub_order_id IS NOT NULL;
