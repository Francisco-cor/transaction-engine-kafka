package com.example.transactionengine.ledger.persistence;

import java.util.UUID;

public record OutboxEvent(
    UUID aggregateId,
    String eventType,
    int schemaVersion,
    String payload,
    String headersJson,
    String partitionKey,
    String topic) {}