package com.example.transactionengine.transaction.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StatementResponse(
    String accountId,
    String currency,
    BigDecimal currentBalance,
    List<Entry> entries) {

  public record Entry(
      UUID ledgerEntryId,
      UUID transactionId,
      BigDecimal amount,
      String direction,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      Instant createdAt) {}
}
