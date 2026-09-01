package com.example.transactionengine.transaction.application;

/**
 * @deprecated Use {@link com.example.transactionengine.observability.TraceContext}.
 */
@Deprecated
public final class TraceContext {

  private TraceContext() {}

  public static String resolve(String candidate) {
    return com.example.transactionengine.observability.TraceContext.resolve(candidate);
  }
}