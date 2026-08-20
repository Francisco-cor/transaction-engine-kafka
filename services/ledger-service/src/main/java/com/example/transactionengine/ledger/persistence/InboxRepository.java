package com.example.transactionengine.ledger.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InboxRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public InboxRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertIfAbsent(
      String consumerName, UUID eventId, UUID transactionId, String payloadHash) {
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
                .addValue("consumerName", consumerName)
                .addValue("eventId", eventId)
                .addValue("transactionId", transactionId)
                .addValue("payloadHash", payloadHash))
        == 1;
  }

  public void markProcessed(String consumerName, UUID eventId) {
    jdbc.update(
        """
        UPDATE transaction_schema.inbox_events
           SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP
         WHERE consumer_name = :consumerName AND event_id = :eventId
        """,
        new MapSqlParameterSource()
            .addValue("consumerName", consumerName)
            .addValue("eventId", eventId));
  }

  public void markDuplicate(String consumerName, UUID eventId) {
    jdbc.update(
        """
        UPDATE transaction_schema.inbox_events
           SET duplicate_count = duplicate_count + 1,
               last_duplicate_at = CURRENT_TIMESTAMP
         WHERE consumer_name = :consumerName AND event_id = :eventId
        """,
        new MapSqlParameterSource()
            .addValue("consumerName", consumerName)
            .addValue("eventId", eventId));
  }
}