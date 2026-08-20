package com.example.transactionengine.reconciliation.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationSnapshot(
    UUID transactionId,
    String transactionStatus,
    String accountId,
    BigDecimal amount,
    String currency,
    String type,
    String transactionReasonCode,
    long createdEventCount,
    long ledgerCount,
    String ledgerAccountId,
    BigDecimal ledgerAmount,
    String ledgerCurrency,
    String ledgerDirection,
    long fraudCount,
    String fraudAccountId,
    BigDecimal fraudAmount,
    String fraudCurrency,
    long fraudEventCount,
    long outcomeEventCount,
    String outcomeEventTypes) {}
