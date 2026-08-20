package com.example.transactionengine.transaction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionRecord(
    UUID transactionId,
    String idempotencyScope,
    String idempotencyKey,
    String requestHash,
    String accountId,
    BigDecimal amount,
    String currency,
    TransactionType type,
    TransactionStatus status,
    String reasonCode,
    Instant createdAt,
    Instant updatedAt) {}