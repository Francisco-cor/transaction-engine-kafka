package com.example.transactionengine.transaction.persistence;

import com.example.transactionengine.transaction.domain.TransactionRecord;
import com.example.transactionengine.transaction.domain.TransactionStatus;
import com.example.transactionengine.transaction.domain.TransactionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

  private static final RowMapper<TransactionRecord> ROW_MAPPER =
      (resultSet, rowNumber) -> mapTransaction(resultSet);

  private final NamedParameterJdbcTemplate jdbc;

  public TransactionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<TransactionRecord> findByIdempotency(String scope, String key) {
    return jdbc
        .query(
            """
            SELECT transaction_id, idempotency_scope, idempotency_key, request_hash,
                   account_id, amount, currency, type, status, reason_code,
                   created_at, updated_at
              FROM transaction_schema.transactions
             WHERE idempotency_scope = :scope AND idempotency_key = :key
            """,
            new MapSqlParameterSource().addValue("scope", scope).addValue("key", key),
            ROW_MAPPER)
        .stream()
        .findFirst();
  }

  public Optional<TransactionRecord> findById(UUID transactionId) {
    return jdbc
        .query(
            """
            SELECT transaction_id, idempotency_scope, idempotency_key, request_hash,
                   account_id, amount, currency, type, status, reason_code,
                   created_at, updated_at
              FROM transaction_schema.transactions
             WHERE transaction_id = :transactionId
            """,
            Map.of("transactionId", transactionId),
            ROW_MAPPER)
        .stream()
        .findFirst();
  }

  public Optional<TransactionRecord> insertIfAbsent(NewTransaction transaction) {
    var parameters =
        new MapSqlParameterSource()
            .addValue("transactionId", transaction.transactionId())
            .addValue("scope", transaction.idempotencyScope())
            .addValue("key", transaction.idempotencyKey())
            .addValue("requestHash", transaction.requestHash())
            .addValue("accountId", transaction.accountId())
            .addValue("amount", transaction.amount())
            .addValue("currency", transaction.currency())
            .addValue("type", transaction.type().name());

    return jdbc
        .query(
            """
            INSERT INTO transaction_schema.transactions (
                transaction_id, idempotency_scope, idempotency_key, request_hash,
                account_id, amount, currency, type, status,
                idempotency_expires_at
            ) VALUES (
                :transactionId, :scope, :key, :requestHash,
                :accountId, :amount, :currency, :type, 'PENDING',
                CURRENT_TIMESTAMP + INTERVAL '7 days'
            )
            ON CONFLICT (idempotency_scope, idempotency_key) DO NOTHING
            RETURNING transaction_id, idempotency_scope, idempotency_key, request_hash,
                      account_id, amount, currency, type, status, reason_code,
                      created_at, updated_at
            """,
            parameters,
            ROW_MAPPER)
        .stream()
        .findFirst();
  }

  private static TransactionRecord mapTransaction(ResultSet resultSet) throws SQLException {
    return new TransactionRecord(
        resultSet.getObject("transaction_id", UUID.class),
        resultSet.getString("idempotency_scope"),
        resultSet.getString("idempotency_key"),
        resultSet.getString("request_hash").trim(),
        resultSet.getString("account_id"),
        resultSet.getBigDecimal("amount"),
        resultSet.getString("currency"),
        TransactionType.valueOf(resultSet.getString("type")),
        TransactionStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("reason_code"),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant());
  }
}