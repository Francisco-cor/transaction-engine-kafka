package com.example.transactionengine.fraud.persistence;

import com.example.transactionengine.fraud.domain.FraudDecision;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FraudDecisionRepository {

  private static final RowMapper<FraudDecision> DECISION_MAPPER =
      (resultSet, rowNumber) -> mapDecision(resultSet);

  private final NamedParameterJdbcTemplate jdbc;

  public FraudDecisionRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<FraudTransaction> findTransaction(UUID transactionId) {
    return jdbc
        .query(
            """
            SELECT transaction_id, account_id, amount, currency
              FROM transaction_schema.transactions
             WHERE transaction_id = :transactionId
            """,
            Map.of("transactionId", transactionId),
            (resultSet, rowNumber) ->
                new FraudTransaction(
                    resultSet.getObject("transaction_id", UUID.class),
                    resultSet.getString("account_id"),
                    resultSet.getBigDecimal("amount"),
                    resultSet.getString("currency")))
        .stream()
        .findFirst();
  }

  public long countRecentTransactions(String accountId, Instant from) {
    return jdbc.queryForObject(
        """
        SELECT count(*)
          FROM transaction_schema.transactions
         WHERE account_id = :accountId AND created_at BETWEEN :from AND CURRENT_TIMESTAMP
        """,
        new MapSqlParameterSource().addValue("accountId", accountId).addValue("from", java.sql.Timestamp.from(from)),
        Long.class);
  }

  public Optional<FraudDecision> findByTransactionId(UUID transactionId) {
    return jdbc
        .query(
            """
            SELECT transaction_id, event_id, account_id, amount, currency,
                   decision, reason_code, rule_code, risk_score, evaluated_at
              FROM transaction_schema.fraud_decisions
             WHERE transaction_id = :transactionId
            """,
            Map.of("transactionId", transactionId),
            DECISION_MAPPER)
        .stream()
        .findFirst();
  }

  public boolean insertIfAbsent(FraudDecision decision) {
    return jdbc.update(
            """
            INSERT INTO transaction_schema.fraud_decisions (
                transaction_id, event_id, account_id, amount, currency,
                decision, reason_code, rule_code, risk_score, evaluated_at
            ) VALUES (
                :transactionId, :eventId, :accountId, :amount, :currency,
                :decision, :reasonCode, :ruleCode, :riskScore, :evaluatedAt
            )
            ON CONFLICT (transaction_id) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("transactionId", decision.transactionId())
                .addValue("eventId", decision.eventId())
                .addValue("accountId", decision.accountId())
                .addValue("amount", decision.amount())
                .addValue("currency", decision.currency())
                .addValue("decision", decision.decision())
                .addValue("reasonCode", decision.reasonCode())
                .addValue("ruleCode", decision.ruleCode())
                .addValue("riskScore", decision.riskScore())
                .addValue("evaluatedAt", java.sql.Timestamp.from(decision.evaluatedAt())))
        == 1;
  }

  private static FraudDecision mapDecision(ResultSet resultSet) throws SQLException {
    return new FraudDecision(
        resultSet.getObject("transaction_id", UUID.class),
        resultSet.getObject("event_id", UUID.class),
        resultSet.getString("account_id"),
        resultSet.getBigDecimal("amount"),
        resultSet.getString("currency"),
        resultSet.getString("decision"),
        resultSet.getString("reason_code"),
        resultSet.getString("rule_code"),
        resultSet.getInt("risk_score"),
        resultSet.getTimestamp("evaluated_at").toInstant());
  }
}
