package com.example.transactionengine.fraud.exception;

public class PermanentFraudException extends RuntimeException {

  public PermanentFraudException(String message) {
    super(message);
  }

  public PermanentFraudException(String message, Throwable cause) {
    super(message, cause);
  }
}
