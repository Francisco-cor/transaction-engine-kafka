package com.example.transactionengine.ledger.exception;

public class RetryableLedgerException extends RuntimeException {

  public RetryableLedgerException(String message) {
    super(message);
  }
}