package com.example.transactionengine.fraud.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FraudDecision(
    UUID transactionId,
    UUID eventId,
    String accountId,
    BigDecimal amount,
    String currency,
    String decision,
    String reasonCode,
    String ruleCode,
    int riskScore,
    Instant evaluatedAt) {}
