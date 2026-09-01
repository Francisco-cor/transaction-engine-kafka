package com.example.transactionengine.fraud.application;

/**
 * @deprecated Use {@link com.example.transactionengine.observability.TraceContext}.
 */
@Deprecated
public final class TraceContext {

  private TraceContext() {}

  public static String resolve(String traceparent) {
    return com.example.transactionengine.observability.TraceContext.resolve(traceparent);
  }
}
