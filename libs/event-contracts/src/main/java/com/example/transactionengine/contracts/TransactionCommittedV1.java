package com.example.transactionengine.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TransactionCommittedV1(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    UUID transactionId,
    String accountId,
    BigDecimal amount,
    String currency,
    String type,
    BigDecimal balanceBefore,
    BigDecimal balanceAfter,
    Map<String, Object> metadata) {}