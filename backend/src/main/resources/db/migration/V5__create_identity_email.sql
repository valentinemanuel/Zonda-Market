CREATE TABLE zonda_market.identity_email_token (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    purpose VARCHAR(24) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX ix_identity_email_token_account ON zonda_market.identity_email_token(account_id, purpose);

CREATE TABLE zonda_market.identity_mail_outbox (
    id UUID PRIMARY KEY REFERENCES zonda_market.identity_email_token(id),
    recipient VARCHAR(254) NOT NULL,
    link_base TEXT NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    provider_id TEXT,
    discarded_at TIMESTAMPTZ
);

CREATE INDEX ix_identity_mail_pending ON zonda_market.identity_mail_outbox(next_attempt_at)
    WHERE sent_at IS NULL AND discarded_at IS NULL;

CREATE TABLE zonda_market.identity_email_rate (
    bucket VARCHAR(90) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    hits INTEGER NOT NULL,
    PRIMARY KEY (bucket, window_start)
);

CREATE INDEX ix_identity_email_rate_window ON zonda_market.identity_email_rate(window_start);
