ALTER TABLE zonda_market.category
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE zonda_market.category_commission_period (
    category_id UUID NOT NULL REFERENCES zonda_market.category (id),
    category_version BIGINT NOT NULL,
    commission_bps INTEGER NOT NULL CHECK (commission_bps BETWEEN 0 AND 10000),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ CHECK (valid_until >= valid_from),
    actor_id UUID REFERENCES zonda_market.user_account (id),
    trace_id UUID,
    PRIMARY KEY (category_id, category_version)
);

CREATE UNIQUE INDEX category_commission_current_idx
    ON zonda_market.category_commission_period (category_id) WHERE valid_until IS NULL;

INSERT INTO zonda_market.category_commission_period
    (category_id, category_version, commission_bps, valid_from)
SELECT id, version, commission_bps, statement_timestamp()
FROM zonda_market.category WHERE NOT deleted;
