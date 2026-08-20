package com.example.transactionengine.ledger.application;

public enum ProcessingOutcome {
  COMMITTED,
  REJECTED,
  DUPLICATE,
  ALREADY_FINAL
}