CREATE TABLE zonda_market.demo_seed_run (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    seeded_at TIMESTAMPTZ NOT NULL
);
