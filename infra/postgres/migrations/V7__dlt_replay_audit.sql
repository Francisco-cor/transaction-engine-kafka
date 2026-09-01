SET search_path TO transaction_schema, public;

CREATE TABLE IF NOT EXISTS dlt_replay_audit (
    audit_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic VARCHAR(255) NOT NULL,
    partition_id INTEGER NOT NULL,
    offset_value BIGINT NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    replay_reason VARCHAR(256) NOT NULL,
    requested_by VARCHAR(256) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT false,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (topic, partition_id, offset_value, consumer_group)
);

CREATE INDEX idx_dlt_replay_audit_consumer ON dlt_replay_audit (consumer_group, created_at);
CREATE INDEX idx_dlt_replay_audit_requested_by ON dlt_replay_audit (requested_by, created_at);

COMMENT ON TABLE dlt_replay_audit IS 'Audit for DLT replay requests via admin endpoint';

GRANT SELECT, INSERT ON dlt_replay_audit TO "${appUser}";
