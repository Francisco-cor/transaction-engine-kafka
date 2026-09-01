SET search_path TO transaction_schema, public;

-- Hardening: idempotency keys should expire (default 7 days) to avoid unbounded growth.
-- Existing rows get expires_at = created_at + 7 days.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS idempotency_expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '7 days';

UPDATE transactions
SET idempotency_expires_at = created_at + INTERVAL '7 days'
WHERE idempotency_expires_at = CURRENT_TIMESTAMP + INTERVAL '7 days'
  AND created_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_transactions_idempotency_expires
    ON transactions (idempotency_expires_at)
    WHERE idempotency_expires_at IS NOT NULL;

-- Ensure currency is always stored uppercase; add check that matches normalized values
-- (Application layer already normalizes via toUpperCase, DB constraint remains [A-Z]{3})
-- Recreate constraint if needed to include comment
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_transactions_currency_upper'
    ) THEN
        ALTER TABLE transactions
            ADD CONSTRAINT chk_transactions_currency_upper CHECK (currency = upper(currency));
    END IF;
END$$;

-- Cleanup helper: keep only expired idempotency rows older than TTL can be removed manually.
-- Periodic cleanup can run: DELETE FROM transaction_schema.transactions WHERE idempotency_expires_at < NOW() AND status IN ('REJECTED','COMMITTED') LIMIT 1000;
COMMENT ON COLUMN transactions.idempotency_expires_at IS 'TTL for idempotency key; default 7 days, configurable via transaction.idempotency.ttl-days';
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA transaction_schema TO "${appUser}";
