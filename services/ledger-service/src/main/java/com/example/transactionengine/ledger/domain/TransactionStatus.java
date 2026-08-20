package com.example.transactionengine.ledger.domain;

public enum TransactionStatus {
  PENDING,
  PROCESSING,
  COMMITTED,
  REJECTED,
  RECONCILIATION_FAILED,
  COMPENSATED
}