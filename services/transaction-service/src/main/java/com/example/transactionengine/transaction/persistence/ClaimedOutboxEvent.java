package com.example.transactionengine.transaction.persistence;

import java.util.UUID;

public record ClaimedOutboxEvent(
    UUID outboxId,
    String eventType,
    int schemaVersion,
    String payload,
    String headersJson,
    String partitionKey,
    int attempts) {}