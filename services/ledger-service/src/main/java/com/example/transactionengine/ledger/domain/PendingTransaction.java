package com.example.transactionengine.ledger.domain;


import java.math.BigDecimal;
import java.util.UUID;

public record PendingTransaction(
    UUID transactionId,
    String accountId,
    BigDecimal amount,
    String currency,
    TransactionType type,
    TransactionStatus status) {}