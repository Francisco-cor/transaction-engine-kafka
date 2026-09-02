-- V11: F7 GDPR + partitioning by range created_at
SET search_path TO transaction_schema, public;

-- GDPR erasure audit table (F7-4)
CREATE TABLE IF NOT EXISTS transaction_schema.gdpr_erasure_requests (
    request_id UUID PRIMARY KEY,
    account_id VARCHAR(128) NOT NULL UNIQUE,
    requested_by VARCHAR(256) NOT NULL,
    reason VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_gdpr_erasure_account ON transaction_schema.gdpr_erasure_requests (account_id);
GRANT SELECT, INSERT, UPDATE ON transaction_schema.gdpr_erasure_requests TO "${appUser}";

-- Partitioning by range created_at for ledger_entries (F7-5)
-- For existing ledger_entries (non-partitioned), we document partitioning strategy and create future partitioned table
-- Real zero-downtime migration would use pg_partman or manual attachment; here we create partitioned variant for new writes
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='transaction_schema' AND tablename='ledger_entries_partitioned') THEN
    CREATE TABLE transaction_schema.ledger_entries_partitioned (
      LIKE transaction_schema.ledger_entries INCLUDING ALL
    ) PARTITION BY RANGE (created_at);
  END IF;
END $$;
-- Grants for partitioned tables (Flyway placeholder)
GRANT SELECT, INSERT ON transaction_schema.ledger_entries_partitioned TO "${appUser}";

-- Create monthly partitions for next 6 months (2026-09 to 2027-02) as example; adjust as needed
DO $$
DECLARE
  m DATE := DATE_TRUNC('month', CURRENT_DATE);
  m_next DATE;
  part_name TEXT;
BEGIN
  FOR i IN 0..5 LOOP
    m_next := m + (i || ' months')::INTERVAL;
    part_name := 'ledger_entries_p_' || TO_CHAR(m_next, 'YYYY_MM');
    IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='transaction_schema' AND tablename=part_name) THEN
      EXECUTE format(
        'CREATE TABLE transaction_schema.%I PARTITION OF transaction_schema.ledger_entries_partitioned FOR VALUES FROM (%L) TO (%L)',
        part_name, m_next, m_next + INTERVAL '1 month');
    END IF;
  END LOOP;
END $$;

-- Transactions partitioning example (same strategy, optional for future)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='transaction_schema' AND tablename='transactions_partitioned') THEN
    CREATE TABLE transaction_schema.transactions_partitioned (
      LIKE transaction_schema.transactions INCLUDING ALL
    ) PARTITION BY RANGE (created_at);
  END IF;
END $$;
GRANT SELECT, INSERT ON transaction_schema.transactions_partitioned TO "${appUser}";

-- Note: ledger_entries BRIN from V9 complements range partitions for time scans; REFRESH account_statement_mv still works
