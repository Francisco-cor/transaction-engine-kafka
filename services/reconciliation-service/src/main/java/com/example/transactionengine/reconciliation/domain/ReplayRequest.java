package com.example.transactionengine.reconciliation.domain;

import java.util.UUID;

public record ReplayRequest(UUID transactionId, String status, int replayCount) {}
