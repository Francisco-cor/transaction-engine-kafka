package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request payload for POST /transactions with validation constraints and optional customerNote (v2).
 *
 * @param accountId account identifier
 * @param amount transaction amount
 * @param type transaction type
 * @param currency ISO 4217 currency
 * @param customerNote optional additive field in v2
 */
public record CreateTransactionRequest(
    @NotBlank @Size(max = 128) String accountId,
    @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
    @NotNull TransactionType type,
    @NotBlank
        @Pattern(regexp = "[a-zA-Z]{3}", message = "currency must be 3 letters (ISO 4217)")
        String currency,
    @Size(max = 256) String customerNote) {
  public CreateTransactionRequest {
    // Allow null customerNote for v1 backward compat
  }

  public CreateTransactionRequest(
      String accountId, BigDecimal amount, TransactionType type, String currency) {
    this(accountId, amount, type, currency, null);
  }
}