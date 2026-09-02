-- V10: F7 CDC — REPLICA IDENTITY FULL + publication for Debezium pgoutput
-- Enables Debezium to capture full row (before+after) for outbox CDC without missing updates
SET search_path TO transaction_schema, public;

-- Grant replication to migrator (Debezium user)
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'transaction_migrator') THEN
    ALTER ROLE transaction_migrator REPLICATION;
  END IF;
END $$;

-- REPLICA IDENTITY FULL for tables captured by Debezium
ALTER TABLE transaction_schema.outbox_events REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.transactions REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.ledger_entries REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.accounts REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.inbox_events REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.fraud_decisions REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.reconciliation_results REPLICA IDENTITY FULL;
ALTER TABLE transaction_schema.notifications REPLICA IDENTITY FULL;

-- Publication for Debezium (filtered autocreate will include tables above)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'debezium_publication') THEN
    CREATE PUBLICATION debezium_publication FOR TABLE
      transaction_schema.outbox_events,
      transaction_schema.transactions,
      transaction_schema.ledger_entries,
      transaction_schema.accounts;
  END IF;
END $$;

-- Optional: ensure wal_level already logical via docker-compose postgres command
-- This migration validates CDC readiness; actual Debezium slot creation happens at connector start
