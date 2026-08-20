package com.example.transactionengine.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record FraudDecisionV1(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    UUID transactionId,
    String accountId,
    BigDecimal amount,
    String currency,
    String decision,
    String reasonCode,
    String ruleCode,
    int riskScore,
    Map<String, Object> metadata) {}
