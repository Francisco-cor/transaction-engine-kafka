package com.example.transactionengine.fraud.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FraudOutboxRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public FraudOutboxRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(OutboxEvent event) {
    jdbc.update(
        """
        INSERT INTO transaction_schema.outbox_events (
            aggregate_id, event_type, schema_version, payload, headers,
            partition_key, topic
        ) VALUES (
            :aggregateId, :eventType, :schemaVersion, CAST(:payload AS JSONB),
            CAST(:headers AS JSONB), :partitionKey, :topic
        )
        """,
        new MapSqlParameterSource()
            .addValue("aggregateId", event.aggregateId())
            .addValue("eventType", event.eventType())
            .addValue("schemaVersion", event.schemaVersion())
            .addValue("payload", event.payload())
            .addValue("headers", event.headersJson())
            .addValue("partitionKey", event.partitionKey())
            .addValue("topic", event.topic()));
  }

  public List<ClaimedOutboxEvent> claim(int batchSize, String owner, int leaseSeconds, String topic) {
    return jdbc.query(
        """
        WITH candidates AS (
            SELECT outbox_id
              FROM transaction_schema.outbox_events
             WHERE topic = :topic
               AND ((status IN ('PENDING', 'FAILED') AND next_attempt_at <= CURRENT_TIMESTAMP)
                OR (status = 'CLAIMED' AND lease_until < CURRENT_TIMESTAMP))
             ORDER BY next_attempt_at, created_at
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
        )
        UPDATE transaction_schema.outbox_events outbox
           SET status = 'CLAIMED',
               claimed_by = :owner,
               lease_until = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second')
          FROM candidates
         WHERE outbox.outbox_id = candidates.outbox_id
        RETURNING outbox.outbox_id, outbox.event_type, outbox.schema_version,
                  outbox.payload::text, outbox.headers::text, outbox.partition_key,
                  outbox.attempts, outbox.topic
        """,
        new MapSqlParameterSource()
            .addValue("batchSize", batchSize)
            .addValue("owner", owner)
            .addValue("leaseSeconds", leaseSeconds)
            .addValue("topic", topic),
        (resultSet, rowNumber) ->
            new ClaimedOutboxEvent(
                resultSet.getObject("outbox_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getString("payload"),
                resultSet.getString("headers"),
                resultSet.getString("partition_key"),
                resultSet.getInt("attempts"),
                resultSet.getString("topic")));
  }

  public void markPublished(UUID outboxId, String owner) {
    jdbc.update(
        """
        UPDATE transaction_schema.outbox_events
           SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
               claimed_by = NULL, lease_until = NULL, last_error = NULL
         WHERE outbox_id = :outboxId AND claimed_by = :owner
        """,
        Map.of("outboxId", outboxId, "owner", owner));
  }

  public void markFailed(UUID outboxId, String owner, long delaySeconds, String error) {
    jdbc.update(
        """
        UPDATE transaction_schema.outbox_events
           SET status = 'FAILED', attempts = attempts + 1,
               next_attempt_at = CURRENT_TIMESTAMP + (:delaySeconds * INTERVAL '1 second'),
               claimed_by = NULL, lease_until = NULL, last_error = :error
         WHERE outbox_id = :outboxId AND claimed_by = :owner
        """,
        new MapSqlParameterSource()
            .addValue("outboxId", outboxId)
            .addValue("owner", owner)
            .addValue("delaySeconds", delaySeconds)
            .addValue("error", error));
  }
}
