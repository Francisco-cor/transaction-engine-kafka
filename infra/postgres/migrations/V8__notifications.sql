SET search_path TO transaction_schema, public;

CREATE TABLE IF NOT EXISTS notifications (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL UNIQUE REFERENCES transactions (transaction_id),
    account_id VARCHAR(128) NOT NULL REFERENCES accounts (account_id),
    amount NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    type VARCHAR(16) NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DLT')),
    channel VARCHAR(32) NOT NULL DEFAULT 'FAKE' CHECK (channel IN ('FAKE', 'EMAIL', 'WEBHOOK')),
    payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_pending ON notifications (status, next_attempt_at) WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_notifications_account ON notifications (account_id, created_at);

COMMENT ON TABLE notifications IS 'Async notification delivery with idempotent dedup per transaction_id';

GRANT SELECT, INSERT, UPDATE, DELETE ON notifications TO "${appUser}";
