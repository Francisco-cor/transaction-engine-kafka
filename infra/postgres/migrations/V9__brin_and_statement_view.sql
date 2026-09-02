-- V9: F6 Performance — BRIN indexes + CQRS statement materialized view
-- BRIN for time-series ledger_entries (cheap for append-only ordered by created_at)
SET search_path TO transaction_schema, public;

-- BRIN on ledger_entries created_at (correlates with insert order)
CREATE INDEX IF NOT EXISTS idx_ledger_entries_brin_created_at
    ON ledger_entries USING BRIN (created_at) WITH (pages_per_range = 128);

-- BRIN on transactions created_at
CREATE INDEX IF NOT EXISTS idx_transactions_brin_created_at
    ON transactions USING BRIN (created_at) WITH (pages_per_range = 128);

-- BRIN on outbox_events created_at for backlog scans
CREATE INDEX IF NOT EXISTS idx_outbox_brin_created_at
    ON outbox_events USING BRIN (created_at) WITH (pages_per_range = 128);

-- Materialized view for CQRS statement read-model (F6)
-- Summary per account: balance + last ledger entry timestamp, refreshed on demand
CREATE MATERIALIZED VIEW IF NOT EXISTS transaction_schema.account_statement_mv AS
SELECT
    a.account_id,
    a.currency,
    a.available_balance,
    a.version,
    a.status,
    COUNT(le.ledger_entry_id) AS entries_count,
    MAX(le.created_at) AS last_entry_at
FROM transaction_schema.accounts a
LEFT JOIN transaction_schema.ledger_entries le ON le.account_id = a.account_id
GROUP BY a.account_id, a.currency, a.available_balance, a.version, a.status;

CREATE UNIQUE INDEX IF NOT EXISTS idx_account_statement_mv_account_id
    ON transaction_schema.account_statement_mv (account_id);

-- Refresh helper: call REFRESH MATERIALIZED VIEW CONCURRENTLY transaction_schema.account_statement_mv
-- Permissions
GRANT SELECT ON transaction_schema.account_statement_mv TO "${appUser}";

-- Index for statement repository query (account_id, created_at DESC) already exists as btree,
-- keep it alongside BRIN for hot-account scans
