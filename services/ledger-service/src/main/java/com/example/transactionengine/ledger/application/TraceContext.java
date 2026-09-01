package com.example.transactionengine.ledger.application;

/**
 * @deprecated Use {@link com.example.transactionengine.observability.TraceContext} from libs/observability.
 * Kept for backward compatibility; delegates to centralized implementation.
 */
@Deprecated
public final class TraceContext {

  private TraceContext() {}

  public static String resolve(String candidate) {
    return com.example.transactionengine.observability.TraceContext.resolve(candidate);
  }
}