package com.example.transactionengine.transaction.persistence;

import com.example.transactionengine.transaction.api.StatementResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatementRepository {

  private final NamedParameterJdbcTemplate jdbc;

  public StatementRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public StatementResponse getStatement(String accountId, int limit) {
    var account = jdbc.query(
        "SELECT available_balance, currency FROM transaction_schema.accounts WHERE account_id=:acc",
        Map.of("acc", accountId),
        (rs, rn) -> Map.of("balance", rs.getBigDecimal("available_balance"), "currency", rs.getString("currency")));
    if (account.isEmpty()) {
      throw new com.example.transactionengine.transaction.exception.TransactionNotFoundException(UUID.nameUUIDFromBytes(accountId.getBytes()));
    }
    var currency = (String) account.get(0).get("currency");
    var balance = (java.math.BigDecimal) account.get(0).get("balance");

    List<StatementResponse.Entry> entries = jdbc.query(
        """
        SELECT ledger_entry_id, transaction_id, amount, direction, balance_before, balance_after, created_at
          FROM transaction_schema.ledger_entries
         WHERE account_id=:acc
         ORDER BY created_at DESC
         LIMIT :limit
        """,
        Map.of("acc", accountId, "limit", limit),
        (rs, rn) -> new StatementResponse.Entry(
            rs.getObject("ledger_entry_id", UUID.class),
            rs.getObject("transaction_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getString("direction"),
            rs.getBigDecimal("balance_before"),
            rs.getBigDecimal("balance_after"),
            rs.getTimestamp("created_at").toInstant()));

    return new StatementResponse(accountId, currency, balance, entries);
  }
}
