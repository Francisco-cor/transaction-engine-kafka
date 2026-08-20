package com.example.transactionengine.reconciliation.domain;

import java.util.Map;

public record Classification(
    ReconciliationStatus status, String reasonCode, Map<String, Object> details) {}
