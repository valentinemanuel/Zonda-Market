CREATE TABLE zonda_market.user_account (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL CONSTRAINT uq_user_account_email UNIQUE,
    password_hash VARCHAR(60) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'SELLER', 'ADMIN')),
    email_verified BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);

CREATE TABLE zonda_market.refresh_session (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES zonda_market.user_account(id),
    jti_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    generation BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted BOOLEAN NOT NULL
);

CREATE INDEX ix_refresh_session_account ON zonda_market.refresh_session(account_id);
