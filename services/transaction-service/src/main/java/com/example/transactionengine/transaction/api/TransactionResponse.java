package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.domain.TransactionRecord;
import com.example.transactionengine.transaction.domain.TransactionStatus;
import com.example.transactionengine.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
    UUID transactionId,
    TransactionStatus status,
    String accountId,
    BigDecimal amount,
    String currency,
    TransactionType type,
    String reasonCode,
    Instant createdAt,
    Instant updatedAt,
    String correlationId) {

  public static TransactionResponse from(TransactionRecord transaction, String correlationId) {
    return new TransactionResponse(
        transaction.transactionId(),
        transaction.status(),
        transaction.accountId(),
        transaction.amount(),
        transaction.currency(),
        transaction.type(),
        transaction.reasonCode(),
        transaction.createdAt(),
        transaction.updatedAt(),
        correlationId);
  }
}