package com.example.transactionengine.reconciliation.persistence;

import java.util.UUID;

public record ReplayRequestData(UUID transactionId, String status, int replayCount) {}
