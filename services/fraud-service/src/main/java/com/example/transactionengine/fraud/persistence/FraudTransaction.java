package com.example.transactionengine.fraud.persistence;

import java.math.BigDecimal;
import java.util.UUID;

public record FraudTransaction(
    UUID transactionId,
    String accountId,
    BigDecimal amount,
    String currency) {}
