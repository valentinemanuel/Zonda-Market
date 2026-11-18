ALTER TABLE zonda_market.user_account ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE zonda_market.oauth_identity (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (issuer, subject),
    UNIQUE (account_id, issuer)
);

CREATE TABLE zonda_market.oauth_attempt (
    state_hash CHAR(64) PRIMARY KEY,
    browser_hash CHAR(64) NOT NULL,
    nonce VARCHAR(64) NOT NULL,
    verifier VARCHAR(64) NOT NULL,
    account_id UUID REFERENCES zonda_market.user_account(id),
    session_id UUID REFERENCES zonda_market.refresh_session(id),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((account_id IS NULL) = (session_id IS NULL))
);

CREATE TABLE zonda_market.oauth_exchange (
    code_hash CHAR(64) PRIMARY KEY,
    browser_hash CHAR(64) NOT NULL,
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    email VARCHAR(254) NOT NULL,
    account_id UUID REFERENCES zonda_market.user_account(id),
    session_id UUID REFERENCES zonda_market.refresh_session(id),
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK ((account_id IS NULL) = (session_id IS NULL))
);

CREATE INDEX ix_oauth_attempt_expiry ON zonda_market.oauth_attempt(expires_at);
CREATE INDEX ix_oauth_exchange_expiry ON zonda_market.oauth_exchange(expires_at);
