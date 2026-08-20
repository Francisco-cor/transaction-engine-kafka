SET search_path TO transaction_schema, public;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS partition_key VARCHAR(128);

UPDATE outbox_events
SET partition_key = aggregate_id::text
WHERE partition_key IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN partition_key SET NOT NULL;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_error TEXT;

CREATE INDEX IF NOT EXISTS idx_outbox_claimable
    ON outbox_events (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'CLAIMED');

INSERT INTO accounts (account_id, currency, available_balance, status)
VALUES
    ('demo-acc-001', 'MXN', 10000.0000, 'ACTIVE'),
    ('demo-acc-002', 'MXN', 10000.0000, 'ACTIVE')
ON CONFLICT (account_id) DO NOTHING;