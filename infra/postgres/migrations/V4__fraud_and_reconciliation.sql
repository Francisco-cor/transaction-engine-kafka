SET search_path TO transaction_schema, public;

CREATE TABLE fraud_decisions (
    fraud_decision_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL UNIQUE REFERENCES transactions (transaction_id),
    event_id UUID NOT NULL,
    account_id VARCHAR(128) NOT NULL REFERENCES accounts (account_id),
    amount NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('PASS', 'REVIEW', 'BLOCK')),
    reason_code VARCHAR(64) NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    risk_score INTEGER NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    evaluated_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_fraud_decisions_event UNIQUE (event_id)
);

CREATE INDEX idx_fraud_decisions_account_evaluated
    ON fraud_decisions (account_id, evaluated_at);

CREATE TABLE reconciliation_results (
    reconciliation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL UNIQUE REFERENCES transactions (transaction_id),
    status VARCHAR(32) NOT NULL CHECK (status IN ('MATCHED', 'MISSING', 'DUPLICATE', 'MISMATCH', 'PENDING')),
    reason_code VARCHAR(64) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    last_checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ,
    last_replay_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reconciliation_pending
    ON reconciliation_results (status, next_attempt_at, last_checked_at)
    WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA transaction_schema TO ${appUser};
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA transaction_schema TO ${appUser};
