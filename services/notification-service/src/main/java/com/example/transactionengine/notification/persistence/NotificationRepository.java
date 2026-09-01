package com.example.transactionengine.notification.persistence;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<UUID> findNotificationId(UUID transactionId) {
    return jdbc.query(
            "SELECT notification_id FROM transaction_schema.notifications WHERE transaction_id = :tx",
            Map.of("tx", transactionId),
            (rs, rn) -> rs.getObject("notification_id", UUID.class))
        .stream()
        .findFirst();
  }

  public boolean insertIfAbsent(UUID transactionId, String accountId, BigDecimal amount, String currency, String type) {
    int updated =
        jdbc.update(
            """
            INSERT INTO transaction_schema.notifications (transaction_id, account_id, amount, currency, type, status)
            VALUES (:tx, :acc, :amount, :curr, :type, 'PENDING')
            ON CONFLICT (transaction_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("tx", transactionId)
                .addValue("acc", accountId)
                .addValue("amount", amount)
                .addValue("curr", currency)
                .addValue("type", type));
    return updated == 1;
  }

  public void markSent(UUID transactionId) {
    jdbc.update(
        "UPDATE transaction_schema.notifications SET status='SENT', updated_at=CURRENT_TIMESTAMP WHERE transaction_id=:tx",
        Map.of("tx", transactionId));
  }

  public void markFailed(UUID transactionId, String error, int attempts) {
    jdbc.update(
        """
        UPDATE transaction_schema.notifications
           SET status='FAILED', last_error=:err, attempts=:attempts, next_attempt_at=CURRENT_TIMESTAMP + INTERVAL '5 seconds', updated_at=CURRENT_TIMESTAMP
         WHERE transaction_id=:tx
        """,
        new MapSqlParameterSource().addValue("tx", transactionId).addValue("err", error).addValue("attempts", attempts));
  }

  public void markDlt(UUID transactionId, String error) {
    jdbc.update(
        "UPDATE transaction_schema.notifications SET status='DLT', last_error=:err, updated_at=CURRENT_TIMESTAMP WHERE transaction_id=:tx",
        new MapSqlParameterSource().addValue("tx", transactionId).addValue("err", error));
  }
}
