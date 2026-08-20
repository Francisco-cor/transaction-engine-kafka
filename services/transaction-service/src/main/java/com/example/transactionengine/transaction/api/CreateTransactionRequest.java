package com.example.transactionengine.transaction.api;

import com.example.transactionengine.transaction.domain.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateTransactionRequest(
    @NotBlank @Size(max = 128) String accountId,
    @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
    @NotNull TransactionType type,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}