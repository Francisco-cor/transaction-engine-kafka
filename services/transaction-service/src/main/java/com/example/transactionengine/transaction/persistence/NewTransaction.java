package com.example.transactionengine.transaction.persistence;

import com.example.transactionengine.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.util.UUID;

public record NewTransaction(
    UUID transactionId,
    String idempotencyScope,
    String idempotencyKey,
    String requestHash,
    String accountId,
    BigDecimal amount,
    String currency,
    TransactionType type) {}