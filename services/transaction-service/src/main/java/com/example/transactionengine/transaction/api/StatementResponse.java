package com.example.transactionengine.transaction.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Account statement read model with current balance and ledger entries.
 *
 * @param accountId account identifier
 * @param currency currency
 * @param currentBalance current balance
 * @param entries recent entries
 */
public record StatementResponse(
    String accountId,
    String currency,
    BigDecimal currentBalance,
    List<Entry> entries) {

  /**
   * Single ledger entry in statement.
   *
   * @param ledgerEntryId ledger entry id
   * @param transactionId transaction id
   * @param amount amount
   * @param direction direction
   * @param balanceBefore balance before
   * @param balanceAfter balance after
   * @param createdAt created at
   */
  public record Entry(
      UUID ledgerEntryId,
      UUID transactionId,
      BigDecimal amount,
      String direction,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      Instant createdAt) {}
}
