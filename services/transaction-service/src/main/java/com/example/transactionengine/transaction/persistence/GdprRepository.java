package com.example.transactionengine.transaction.persistence;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GdprRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public GdprRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void recordErasure(String accountId, String requestedBy, String reason) {
    jdbc.update(
        """
        INSERT INTO transaction_schema.gdpr_erasure_requests (request_id, account_id, requested_by, reason, created_at)
        VALUES (:requestId, :accountId, :requestedBy, :reason, CURRENT_TIMESTAMP)
        ON CONFLICT (account_id) DO UPDATE SET requested_by=:requestedBy, reason=:reason, created_at=CURRENT_TIMESTAMP
        """,
        Map.of(
            "requestId", UUID.randomUUID(),
            "accountId", accountId,
            "requestedBy", requestedBy,
            "reason", reason));
  }

  public int scrubCustomerNote(String accountId) {
    // Scrub customerNote from outbox payloads where accountId matches JSON field
    // Payload is JSONB: {"customerNote":"..."} — remove key if exists
    return jdbc.update(
        """
        UPDATE transaction_schema.outbox_events
           SET payload = payload - 'customerNote' - 'customerNoteVault'
         WHERE payload->>'accountId' = :accountId
           AND (payload ? 'customerNote' OR payload ? 'customerNoteVault')
        """,
        Map.of("accountId", accountId));
  }

  public int anonymizeTransactions(String accountId) {
    // For GDPR, we keep ledger financial data but anonymize request_hash linkage
    // Set idempotency_scope to anonymized marker so future erasures don't leak original scope
    return jdbc.update(
        """
        UPDATE transaction_schema.transactions
           SET idempotency_scope = 'erased-' || :accountId
         WHERE account_id = :accountId AND idempotency_scope NOT LIKE 'erased-%'
        """,
        Map.of("accountId", accountId));
  }
}
