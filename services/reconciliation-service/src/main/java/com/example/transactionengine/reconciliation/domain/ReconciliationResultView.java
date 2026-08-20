package com.example.transactionengine.reconciliation.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReconciliationResultView(
    UUID transactionId,
    ReconciliationStatus status,
    String reasonCode,
    Map<String, Object> details,
    int attempts,
    int replayCount,
    Instant lastCheckedAt,
    Instant nextAttemptAt,
    Instant lastReplayAt) {}
