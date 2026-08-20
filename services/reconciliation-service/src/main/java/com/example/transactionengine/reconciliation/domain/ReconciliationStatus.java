package com.example.transactionengine.reconciliation.domain;

public enum ReconciliationStatus {
  MATCHED,
  MISSING,
  DUPLICATE,
  MISMATCH,
  PENDING
}
