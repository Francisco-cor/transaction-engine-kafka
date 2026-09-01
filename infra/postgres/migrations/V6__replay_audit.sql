SET search_path TO transaction_schema, public;

CREATE TABLE IF NOT EXISTS reconciliation_replay_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES transactions (transaction_id),
    reason VARCHAR(256) NOT NULL,
    requested_by VARCHAR(256) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT false,
    previous_status VARCHAR(32) NOT NULL,
    new_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_replay_audit_transaction ON reconciliation_replay_audit (transaction_id, created_at);
CREATE INDEX idx_replay_audit_requested_by ON reconciliation_replay_audit (requested_by, created_at);

COMMENT ON TABLE reconciliation_replay_audit IS 'Audit for controlled reconciliation replays, required for admin:replay scope';

GRANT SELECT, INSERT ON reconciliation_replay_audit TO "${appUser}";
