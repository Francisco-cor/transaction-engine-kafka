package com.example.transactionengine.fraud.exception;

public class RetryableFraudException extends RuntimeException {

  public RetryableFraudException(String message) {
    super(message);
  }
}
