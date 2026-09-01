package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.domain.TransactionRecord;
import com.example.transactionengine.transaction.domain.TransactionStatus;
import com.example.transactionengine.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * API response for a transaction including status and metadata.
 *
 * @param transactionId transaction id
 * @param status status
 * @param accountId account id
 * @param amount amount
 * @param currency currency
 * @param type type
 * @param reasonCode reason code
 * @param createdAt created at
 * @param updatedAt updated at
 * @param correlationId correlation id
 */
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

  /**
   * Builds response from domain record.
   *
   * @param transaction domain record
   * @param correlationId correlation id
   * @return response
   */
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