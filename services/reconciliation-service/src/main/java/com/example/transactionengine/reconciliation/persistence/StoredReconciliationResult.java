package com.example.transactionengine.reconciliation.persistence;

import java.time.Instant;
import java.util.UUID;

public record StoredReconciliationResult(
    UUID transactionId,
    String status,
    String reasonCode,
    String detailsJson,
    int attempts,
    int replayCount,
    Instant lastCheckedAt,
    Instant nextAttemptAt,
    Instant lastReplayAt) {}
