CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET search_path TO transaction_schema, public;

CREATE TABLE accounts (
    account_id VARCHAR(128) PRIMARY KEY,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    available_balance NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transactions (
    transaction_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_scope VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    account_id VARCHAR(128) NOT NULL REFERENCES accounts (account_id),
    amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    type VARCHAR(16) NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMMITTED', 'REJECTED', 'RECONCILIATION_FAILED', 'COMPENSATED')),
    reason_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transactions_idempotency UNIQUE (idempotency_scope, idempotency_key)
);

CREATE INDEX idx_transactions_account_created_at ON transactions (account_id, created_at);

CREATE TABLE ledger_entries (
    ledger_entry_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL UNIQUE REFERENCES transactions (transaction_id),
    account_id VARCHAR(128) NOT NULL REFERENCES accounts (account_id),
    amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    direction VARCHAR(16) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance_before NUMERIC(19, 4) NOT NULL CHECK (balance_before >= 0),
    balance_after NUMERIC(19, 4) NOT NULL CHECK (balance_after >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_entries_account_created_at ON ledger_entries (account_id, created_at);

CREATE TABLE inbox_events (
    consumer_name VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    transaction_id UUID,
    payload_hash CHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'DUPLICATE', 'FAILED')),
    failure_count INTEGER NOT NULL DEFAULT 0 CHECK (failure_count >= 0),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE outbox_events (
    outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox_events (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'CLAIMED', 'FAILED');

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA transaction_schema TO "${appUser}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA transaction_schema TO "${appUser}";
