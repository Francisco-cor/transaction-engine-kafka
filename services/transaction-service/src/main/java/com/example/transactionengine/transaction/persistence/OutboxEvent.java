package com.example.transactionengine.transaction.persistence;

import java.util.UUID;

public record OutboxEvent(
    UUID aggregateId,
    String eventType,
    int schemaVersion,
    String payload,
    String headersJson,
    String partitionKey,
    String topic) {}