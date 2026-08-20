package com.example.transactionengine.fraud.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FraudInboxRepository {

  private static final String CONSUMER_NAME = "fraud-service";
  private final NamedParameterJdbcTemplate jdbc;

  public FraudInboxRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertIfAbsent(UUID eventId, UUID transactionId, String payloadHash) {
    return jdbc.update(
            """
            INSERT INTO transaction_schema.inbox_events (
                consumer_name, event_id, transaction_id, payload_hash, status
            ) VALUES (
                :consumerName, :eventId, :transactionId, :payloadHash, 'RECEIVED'
            )
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("consumerName", CONSUMER_NAME)
                .addValue("eventId", eventId)
                .addValue("transactionId", transactionId)
                .addValue("payloadHash", payloadHash))
        == 1;
  }

  public void markProcessed(UUID eventId) {
    jdbc.update(
        """
        UPDATE transaction_schema.inbox_events
           SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP
         WHERE consumer_name = :consumerName AND event_id = :eventId
        """,
        new MapSqlParameterSource().addValue("consumerName", CONSUMER_NAME).addValue("eventId", eventId));
  }

  public void markDuplicate(UUID eventId) {
    jdbc.update(
        """
        UPDATE transaction_schema.inbox_events
           SET status = 'DUPLICATE',
               duplicate_count = duplicate_count + 1,
               last_duplicate_at = CURRENT_TIMESTAMP
         WHERE consumer_name = :consumerName AND event_id = :eventId
        """,
        new MapSqlParameterSource().addValue("consumerName", CONSUMER_NAME).addValue("eventId", eventId));
  }
}
