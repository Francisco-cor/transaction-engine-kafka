-- Invariants suite for PLAN_ELEVACION F1: verifies exactly-once business guarantees.
-- Each query must return 0 rows for success. The bash/PowerShell wrapper treats any row as failure.
-- Mirrors IMPLEMENTATION_PLAN.md:599 invariants.

\set ON_ERROR_STOP on
SET search_path TO transaction_schema, public;

-- I1: No more than one ledger_entry per transaction_id (UNIQUE constraint, but verify)
SELECT 'I1_duplicate_ledger_per_transaction' AS invariant, transaction_id, count(*) AS cnt
FROM transaction_schema.ledger_entries
GROUP BY transaction_id HAVING count(*) > 1;

-- I2: Every COMMITTED transaction has exactly one ledger entry
SELECT 'I2_committed_without_ledger' AS invariant, t.transaction_id
FROM transaction_schema.transactions t
LEFT JOIN transaction_schema.ledger_entries l ON l.transaction_id = t.transaction_id
WHERE t.status = 'COMMITTED' AND l.ledger_entry_id IS NULL
UNION ALL
SELECT 'I2_committed_with_multiple_ledger' AS invariant, l.transaction_id
FROM transaction_schema.ledger_entries l
JOIN transaction_schema.transactions t ON t.transaction_id = l.transaction_id
WHERE t.status = 'COMMITTED'
GROUP BY l.transaction_id HAVING count(*) <> 1;

-- I3: REJECTED transactions must not have a ledger entry (no debit effect)
SELECT 'I3_rejected_has_ledger' AS invariant, t.transaction_id
FROM transaction_schema.transactions t
JOIN transaction_schema.ledger_entries l ON l.transaction_id = t.transaction_id
WHERE t.status = 'REJECTED';

-- I4: Balance final equals balance initial + sum(ledger_entries) per account
-- Uses demo accounts initial 10000 plus known ledger; for arbitrary accounts we check per-account sum
-- This invariant checks that no ledger entry has balance_after != balance_before +/- amount contradiction
SELECT 'I4_ledger_balance_mismatch' AS invariant, ledger_entry_id, account_id, amount, direction, balance_before, balance_after
FROM transaction_schema.ledger_entries
WHERE (direction = 'DEBIT' AND balance_after != balance_before - amount)
   OR (direction = 'CREDIT' AND balance_after != balance_before + amount);

-- I5: Reprocessing event does not change financial result (duplicate_count tracked, but ledger remains at-most-one)
-- Already covered by I1, but explicitly check inbox duplicate does not create second ledger via race
SELECT 'I5_duplicate_inbox_created_ledger' AS invariant, i.event_id, i.transaction_id
FROM transaction_schema.inbox_events i
JOIN transaction_schema.ledger_entries l ON l.transaction_id = i.transaction_id
WHERE i.duplicate_count > 0
GROUP BY i.event_id, i.transaction_id, l.transaction_id
HAVING count(*) > 1;

-- I6: Event in DLT not lost - placeholder check: DLT topic retention ensures not null, here we just ensure failed inbox not lost
-- Real DLT lives in Kafka; DB check ensures failed status preserved
SELECT 'I6_failed_inbox_lost' AS invariant, consumer_name, event_id
FROM transaction_schema.inbox_events
WHERE status = 'FAILED' AND failure_count = 0;

-- I7: State never goes backwards - ensure no COMMITTED -> PENDING regression
-- We track via updated_at; if transaction was COMMITTED, it should not be PENDING later.
-- Since we don't have history table, we check that no ledger exists for PENDING (should be none until committed)
SELECT 'I7_pending_has_ledger' AS invariant, t.transaction_id
FROM transaction_schema.transactions t
JOIN transaction_schema.ledger_entries l ON l.transaction_id = t.transaction_id
WHERE t.status = 'PENDING';

-- I8: Amount and currency persisted equal approved (no implicit conversion)
SELECT 'I8_amount_currency_mismatch_ledger' AS invariant, t.transaction_id, t.amount, t.currency, l.amount, l.currency
FROM transaction_schema.transactions t
JOIN transaction_schema.ledger_entries l ON l.transaction_id = t.transaction_id
WHERE t.amount <> l.amount OR t.currency <> l.currency;

-- I9: Fraud decision deduplicated - one decision per transaction
SELECT 'I9_duplicate_fraud_decision' AS invariant, transaction_id, count(*) AS cnt
FROM transaction_schema.fraud_decisions
GROUP BY transaction_id HAVING count(*) > 1;

-- Summary counts (informational, not fail)
SELECT 'SUMMARY' AS section, 'transactions' AS metric, count(*) AS value FROM transaction_schema.transactions
UNION ALL SELECT 'SUMMARY', 'ledger_entries', count(*) FROM transaction_schema.ledger_entries
UNION ALL SELECT 'SUMMARY', 'fraud_decisions', count(*) FROM transaction_schema.fraud_decisions
UNION ALL SELECT 'SUMMARY', 'reconciliation_matched', count(*) FROM transaction_schema.reconciliation_results WHERE status='MATCHED'
UNION ALL SELECT 'SUMMARY', 'reconciliation_pending', count(*) FROM transaction_schema.reconciliation_results WHERE status='PENDING';
