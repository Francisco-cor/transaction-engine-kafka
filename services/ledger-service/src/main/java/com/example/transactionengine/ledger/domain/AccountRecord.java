package com.example.transactionengine.ledger.domain;

import java.math.BigDecimal;

public record AccountRecord(
    String accountId,
    String currency,
    BigDecimal availableBalance,
    long version,
    String status) {}