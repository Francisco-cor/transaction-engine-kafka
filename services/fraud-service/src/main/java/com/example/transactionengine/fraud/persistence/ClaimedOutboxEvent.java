package com.example.transactionengine.fraud.persistence;

import java.util.UUID;

public record ClaimedOutboxEvent(
    UUID outboxId,
    String eventType,
    int schemaVersion,
    String payload,
    String headersJson,
    String partitionKey,
    int attempts,
    String topic) {}
