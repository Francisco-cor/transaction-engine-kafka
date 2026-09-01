package com.example.transactionengine.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Envelope for the first version of the transaction-created event. Tolerates v2 additive fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionCreatedV1(
    UUID eventId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    UUID transactionId,
    String accountId,
    BigDecimal amount,
    String currency,
    String type,
    Map<String, Object> metadata) {}
