package com.example.transactionengine.reconciliation.persistence;

import com.example.transactionengine.reconciliation.domain.ReconciliationSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationRepository {

  private static final RowMapper<ReconciliationSnapshot> SNAPSHOT_MAPPER =
      (resultSet, rowNumber) -> mapSnapshot(resultSet);
  private static final RowMapper<StoredReconciliationResult> RESULT_MAPPER =
      (resultSet, rowNumber) -> mapResult(resultSet);

  private final NamedParameterJdbcTemplate jdbc;

  public ReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<UUID> findCandidates(int batchSize) {
    return jdbc.queryForList(
        """
        SELECT t.transaction_id
          FROM transaction_schema.transactions t
          LEFT JOIN transaction_schema.reconciliation_results r
            ON r.transaction_id = t.transaction_id
         WHERE r.transaction_id IS NULL
            OR (r.status = 'PENDING'
                AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= CURRENT_TIMESTAMP))
         ORDER BY t.created_at
         LIMIT :batchSize
        """,
        Map.of("batchSize", batchSize),
        UUID.class);
  }

  public Optional<ReconciliationSnapshot> findSnapshot(UUID transactionId) {
    return jdbc
        .query(
            """
            SELECT t.transaction_id, t.status, t.account_id, t.amount, t.currency,
                   t.type, t.reason_code,
                   (SELECT count(*) FROM transaction_schema.outbox_events o
                     WHERE o.aggregate_id = t.transaction_id
                       AND o.event_type = 'TransactionCreated') AS created_event_count,
                   (SELECT count(*) FROM transaction_schema.ledger_entries l
                     WHERE l.transaction_id = t.transaction_id) AS ledger_count,
                   (SELECT max(l.account_id) FROM transaction_schema.ledger_entries l
                     WHERE l.transaction_id = t.transaction_id) AS ledger_account_id,
                   (SELECT max(l.amount) FROM transaction_schema.ledger_entries l
                     WHERE l.transaction_id = t.transaction_id) AS ledger_amount,
                   (SELECT max(l.currency) FROM transaction_schema.ledger_entries l
                     WHERE l.transaction_id = t.transaction_id) AS ledger_currency,
                   (SELECT max(l.direction) FROM transaction_schema.ledger_entries l
                     WHERE l.transaction_id = t.transaction_id) AS ledger_direction,
                   (SELECT count(*) FROM transaction_schema.fraud_decisions f
                     WHERE f.transaction_id = t.transaction_id) AS fraud_count,
                   (SELECT max(f.account_id) FROM transaction_schema.fraud_decisions f
                     WHERE f.transaction_id = t.transaction_id) AS fraud_account_id,
                   (SELECT max(f.amount) FROM transaction_schema.fraud_decisions f
                     WHERE f.transaction_id = t.transaction_id) AS fraud_amount,
                   (SELECT max(f.currency) FROM transaction_schema.fraud_decisions f
                     WHERE f.transaction_id = t.transaction_id) AS fraud_currency,
                   (SELECT count(*) FROM transaction_schema.outbox_events o
                     WHERE o.aggregate_id = t.transaction_id
                       AND o.event_type = 'FraudDecision') AS fraud_event_count,
                   (SELECT count(*) FROM transaction_schema.outbox_events o
                     WHERE o.aggregate_id = t.transaction_id
                       AND o.event_type IN ('TransactionCommitted', 'TransactionRejected'))
                     AS outcome_event_count,
                   (SELECT string_agg(o.event_type, ',')
                      FROM transaction_schema.outbox_events o
                     WHERE o.aggregate_id = t.transaction_id
                       AND o.event_type IN ('TransactionCommitted', 'TransactionRejected'))
                     AS outcome_event_types
              FROM transaction_schema.transactions t
             WHERE t.transaction_id = :transactionId
            """,
            Map.of("transactionId", transactionId),
            SNAPSHOT_MAPPER)
        .stream()
        .findFirst();
  }

  public void upsertResult(
      UUID transactionId,
      String status,
      String reasonCode,
      String detailsJson,
      long pendingRetrySeconds) {
    jdbc.update(
        """
        INSERT INTO transaction_schema.reconciliation_results (
            transaction_id, status, reason_code, details, attempts, next_attempt_at
        ) VALUES (
            :transactionId, :status, :reasonCode, CAST(:details AS JSONB), 1,
            CASE WHEN :status = 'PENDING'
                 THEN CURRENT_TIMESTAMP + (:pendingRetrySeconds * INTERVAL '1 second')
                 ELSE NULL END
        )
        ON CONFLICT (transaction_id) DO UPDATE
           SET status = EXCLUDED.status,
               reason_code = EXCLUDED.reason_code,
               details = EXCLUDED.details,
               attempts = reconciliation_results.attempts + 1,
               last_checked_at = CURRENT_TIMESTAMP,
               next_attempt_at = EXCLUDED.next_attempt_at,
               updated_at = CURRENT_TIMESTAMP
        """,
        new MapSqlParameterSource()
            .addValue("transactionId", transactionId)
            .addValue("status", status)
            .addValue("reasonCode", reasonCode)
            .addValue("details", detailsJson)
            .addValue("pendingRetrySeconds", pendingRetrySeconds));
  }

  public Optional<StoredReconciliationResult> findResult(UUID transactionId) {
    return jdbc
        .query(
            """
            SELECT transaction_id, status, reason_code, details::text, attempts, replay_count,
                   last_checked_at, next_attempt_at, last_replay_at
              FROM transaction_schema.reconciliation_results
             WHERE transaction_id = :transactionId
            """,
            Map.of("transactionId", transactionId),
            RESULT_MAPPER)
        .stream()
        .findFirst();
  }

  public ReplayRequestData requestReplay(
      UUID transactionId, String topic, String reason, String requestedBy, boolean dryRun) {
    var eventIds =
        jdbc.queryForList(
            """
            SELECT outbox_id
              FROM transaction_schema.outbox_events
             WHERE aggregate_id = :transactionId
               AND event_type = 'TransactionCreated'
               AND topic = :topic
             ORDER BY created_at
             LIMIT 2
            """,
            new MapSqlParameterSource().addValue("transactionId", transactionId).addValue("topic", topic),
            UUID.class);
    if (eventIds.size() != 1) {
      throw new IllegalStateException(
          "Controlled replay requires exactly one TransactionCreated outbox event");
    }

    var statusBefore =
        jdbc.queryForObject(
            "SELECT status FROM transaction_schema.reconciliation_results WHERE transaction_id = :transactionId",
            Map.of("transactionId", transactionId),
            String.class);

    jdbc.update(
        """
        INSERT INTO transaction_schema.reconciliation_replay_audit (
            transaction_id, reason, requested_by, dry_run, previous_status, new_status
        ) VALUES (:transactionId, :reason, :requestedBy, :dryRun, :previousStatus, 'PENDING')
        """,
        new MapSqlParameterSource()
            .addValue("transactionId", transactionId)
            .addValue("reason", reason)
            .addValue("requestedBy", requestedBy)
            .addValue("dryRun", dryRun)
            .addValue("previousStatus", statusBefore != null ? statusBefore : "UNKNOWN"));

    if (dryRun) {
      return new ReplayRequestData(transactionId, statusBefore != null ? statusBefore : "UNKNOWN", 0);
    }

    var resultUpdated =
        jdbc.update(
            """
            UPDATE transaction_schema.reconciliation_results
               SET status = 'PENDING',
                   reason_code = 'REPLAY_REQUESTED',
                   details = details || jsonb_build_object('replayReason', :reason),
                   replay_count = replay_count + 1,
                   last_replay_at = CURRENT_TIMESTAMP,
                   next_attempt_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP
             WHERE transaction_id = :transactionId AND status <> 'MATCHED'
            """,
            new MapSqlParameterSource()
                .addValue("transactionId", transactionId)
                .addValue("reason", reason));
    if (resultUpdated != 1) {
      throw new IllegalStateException(
          "Replay is only allowed for a non-MATCHED reconciliation result");
    }

    jdbc.update(
        """
        UPDATE transaction_schema.outbox_events
           SET status = 'PENDING',
               next_attempt_at = CURRENT_TIMESTAMP,
               claimed_by = NULL,
               lease_until = NULL,
               last_error = :lastError
         WHERE outbox_id = :outboxId
        """,
        new MapSqlParameterSource()
            .addValue("outboxId", eventIds.getFirst())
            .addValue("lastError", "controlled reconciliation replay: " + reason + " by " + requestedBy));

    return jdbc
        .query(
            """
            SELECT transaction_id, status, replay_count
              FROM transaction_schema.reconciliation_results
             WHERE transaction_id = :transactionId
            """,
            Map.of("transactionId", transactionId),
            (resultSet, rowNumber) ->
                new ReplayRequestData(
                    resultSet.getObject("transaction_id", UUID.class),
                    resultSet.getString("status"),
                    resultSet.getInt("replay_count")))
        .stream()
        .findFirst()
        .orElseThrow();
  }

  public ReplayRequestData requestReplay(UUID transactionId, String topic, String reason) {
    return requestReplay(transactionId, topic, reason, "anonymous", false);
  }

  private static ReconciliationSnapshot mapSnapshot(ResultSet resultSet) throws SQLException {
    return new ReconciliationSnapshot(
        resultSet.getObject("transaction_id", UUID.class),
        resultSet.getString("status"),
        resultSet.getString("account_id"),
        resultSet.getBigDecimal("amount"),
        resultSet.getString("currency"),
        resultSet.getString("type"),
        resultSet.getString("reason_code"),
        resultSet.getLong("created_event_count"),
        resultSet.getLong("ledger_count"),
        resultSet.getString("ledger_account_id"),
        resultSet.getBigDecimal("ledger_amount"),
        resultSet.getString("ledger_currency"),
        resultSet.getString("ledger_direction"),
        resultSet.getLong("fraud_count"),
        resultSet.getString("fraud_account_id"),
        resultSet.getBigDecimal("fraud_amount"),
        resultSet.getString("fraud_currency"),
        resultSet.getLong("fraud_event_count"),
        resultSet.getLong("outcome_event_count"),
        resultSet.getString("outcome_event_types"));
  }

  private static StoredReconciliationResult mapResult(ResultSet resultSet) throws SQLException {
    return new StoredReconciliationResult(
        resultSet.getObject("transaction_id", UUID.class),
        resultSet.getString("status"),
        resultSet.getString("reason_code"),
        resultSet.getString("details"),
        resultSet.getInt("attempts"),
        resultSet.getInt("replay_count"),
        resultSet.getTimestamp("last_checked_at").toInstant(),
        resultSet.getTimestamp("next_attempt_at") == null
            ? null
            : resultSet.getTimestamp("next_attempt_at").toInstant(),
        resultSet.getTimestamp("last_replay_at") == null
            ? null
            : resultSet.getTimestamp("last_replay_at").toInstant());
  }
}
