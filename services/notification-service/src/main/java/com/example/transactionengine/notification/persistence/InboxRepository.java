package com.example.transactionengine.notification.persistence;

import java.util.Map;
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

  public boolean insertIfAbsent(UUID eventId, UUID transactionId, String payloadHash) {
    int updated =
        jdbc.update(
            """
            INSERT INTO transaction_schema.inbox_events (consumer_name, event_id, transaction_id, payload_hash, status)
            VALUES ('notification-service', :eventId, :tx, :hash, 'RECEIVED')
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("tx", transactionId)
                .addValue("hash", payloadHash));
    return updated == 1;
  }

  public void markProcessed(UUID eventId) {
    jdbc.update(
        "UPDATE transaction_schema.inbox_events SET status='PROCESSED', processed_at=CURRENT_TIMESTAMP WHERE consumer_name='notification-service' AND event_id=:id",
        Map.of("id", eventId));
  }

  public void markDuplicate(UUID eventId) {
    jdbc.update(
        "UPDATE transaction_schema.inbox_events SET status='DUPLICATE', duplicate_count=duplicate_count+1, last_duplicate_at=CURRENT_TIMESTAMP WHERE consumer_name='notification-service' AND event_id=:id",
        Map.of("id", eventId));
  }
}
