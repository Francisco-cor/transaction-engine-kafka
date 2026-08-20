package com.example.transactionengine.ledger.persistence;

import com.example.transactionengine.ledger.domain.AccountRecord;
import com.example.transactionengine.ledger.domain.PendingTransaction;
import com.example.transactionengine.ledger.domain.TransactionStatus;
import com.example.transactionengine.ledger.domain.TransactionType;
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
public class LedgerRepository {

  private static final RowMapper<PendingTransaction> TRANSACTION_MAPPER =
      (resultSet, rowNumber) -> mapTransaction(resultSet);
  private static final RowMapper<AccountRecord> ACCOUNT_MAPPER =
      (resultSet, rowNumber) -> mapAccount(resultSet);

  private final NamedParameterJdbcTemplate jdbc;

  public LedgerRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PendingTransaction> findTransactionForUpdate(UUID transactionId) {
    return jdbc
        .query(
            """
            SELECT transaction_id, account_id, amount, currency, type, status
              FROM transaction_schema.transactions
             WHERE transaction_id = :transactionId
             FOR UPDATE
            """,
            Map.of("transactionId", transactionId),
            TRANSACTION_MAPPER)
        .stream()
        .findFirst();
  }

  public Optional<AccountRecord> lockAccount(String accountId) {
    return jdbc
        .query(
            """
            SELECT account_id, currency, available_balance, version, status
              FROM transaction_schema.accounts
             WHERE account_id = :accountId
             FOR UPDATE
            """,
            Map.of("accountId", accountId),
            ACCOUNT_MAPPER)
        .stream()
        .findFirst();
  }

  public void insertLedgerEntry(
      UUID transactionId,
      String accountId,
      java.math.BigDecimal amount,
      TransactionType type,
      String currency,
      java.math.BigDecimal balanceBefore,
      java.math.BigDecimal balanceAfter) {
    jdbc.update(
        """
        INSERT INTO transaction_schema.ledger_entries (
            transaction_id, account_id, amount, direction, currency,
            balance_before, balance_after
        ) VALUES (
            :transactionId, :accountId, :amount, :direction, :currency,
            :balanceBefore, :balanceAfter
        )
        """,
        new MapSqlParameterSource()
            .addValue("transactionId", transactionId)
            .addValue("accountId", accountId)
            .addValue("amount", amount)
            .addValue("direction", type.name())
            .addValue("currency", currency)
            .addValue("balanceBefore", balanceBefore)
            .addValue("balanceAfter", balanceAfter));
  }

  public void updateAccount(String accountId, java.math.BigDecimal balanceAfter) {
    jdbc.update(
        """
        UPDATE transaction_schema.accounts
           SET available_balance = :balanceAfter,
               version = version + 1,
               updated_at = CURRENT_TIMESTAMP
         WHERE account_id = :accountId
        """,
        Map.of("accountId", accountId, "balanceAfter", balanceAfter));
  }

  public void markCommitted(UUID transactionId) {
    var updated =
        jdbc.update(
            """
            UPDATE transaction_schema.transactions
               SET status = 'COMMITTED', reason_code = NULL, updated_at = CURRENT_TIMESTAMP
             WHERE transaction_id = :transactionId AND status = 'PENDING'
            """,
            Map.of("transactionId", transactionId));
    if (updated != 1) {
      throw new IllegalStateException("Transaction was not pending while committing: " + transactionId);
    }
  }

  public void markRejected(UUID transactionId, String reasonCode) {
    var updated =
        jdbc.update(
            """
            UPDATE transaction_schema.transactions
               SET status = 'REJECTED', reason_code = :reasonCode,
                   updated_at = CURRENT_TIMESTAMP
             WHERE transaction_id = :transactionId AND status = 'PENDING'
            """,
            new MapSqlParameterSource()
                .addValue("transactionId", transactionId)
                .addValue("reasonCode", reasonCode));
    if (updated != 1) {
      throw new IllegalStateException("Transaction was not pending while rejecting: " + transactionId);
    }
  }

  private static PendingTransaction mapTransaction(ResultSet resultSet) throws SQLException {
    return new PendingTransaction(
        resultSet.getObject("transaction_id", UUID.class),
        resultSet.getString("account_id"),
        resultSet.getBigDecimal("amount"),
        resultSet.getString("currency"),
        TransactionType.valueOf(resultSet.getString("type")),
        TransactionStatus.valueOf(resultSet.getString("status")));
  }

  private static AccountRecord mapAccount(ResultSet resultSet) throws SQLException {
    return new AccountRecord(
        resultSet.getString("account_id"),
        resultSet.getString("currency"),
        resultSet.getBigDecimal("available_balance"),
        resultSet.getLong("version"),
        resultSet.getString("status"));
  }
}