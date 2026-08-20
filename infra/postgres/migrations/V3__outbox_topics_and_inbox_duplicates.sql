SET search_path TO transaction_schema, public;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS topic VARCHAR(255);

UPDATE outbox_events
SET topic = 'transactions.created.v1'
WHERE topic IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN topic SET NOT NULL;

ALTER TABLE inbox_events
    ADD COLUMN IF NOT EXISTS duplicate_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_duplicate_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_outbox_topic_claimable
    ON outbox_events (topic, status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'CLAIMED');