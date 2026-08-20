package com.example.transactionengine.transaction.domain;

public enum TransactionStatus {
  PENDING,
  PROCESSING,
  COMMITTED,
  REJECTED,
  RECONCILIATION_FAILED,
  COMPENSATED
}