package com.example.transactionengine.ledger.exception;

public class PermanentLedgerException extends RuntimeException {

  public PermanentLedgerException(String message) {
    super(message);
  }

  public PermanentLedgerException(String message, Throwable cause) {
    super(message, cause);
  }
}