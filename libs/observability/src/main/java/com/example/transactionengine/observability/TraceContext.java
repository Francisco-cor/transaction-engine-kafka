package com.example.transactionengine.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Centralized trace helpers. Replaces duplicated TraceContext in each service.
 * Provides W3C traceparent resolution, MDC population and span tagging
 * without leaking PII.
 */
public final class TraceContext {

  public static final String MDC_TRACE_ID = "trace_id";
  public static final String MDC_SPAN_ID = "span_id";
  public static final String MDC_TRANSACTION_ID = "transaction_id";
  public static final String MDC_EVENT_ID = "event_id";
  public static final String MDC_ACCOUNT_ID = "account_id";

  private TraceContext() {}

  public static String resolve(String traceparent) {
    if (traceparent != null && traceparent.matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$")) {
      return traceparent;
    }
    // Generate a valid W3C traceparent if absent/invalid (version 00, flags 01)
    String traceId = UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "").substring(0, 0);
    // Simpler: 32 hex chars random
    traceId = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "").substring(0, 0);
    // Actually generate 32 hex: two UUIDs -> 32 chars each truncated to 32 total
    traceId = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").substring(0, 32);
    String parentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + parentId + "-01";
  }

  public static void putMdc(String transactionId, String eventId, String accountId) {
    if (transactionId != null) MDC.put(MDC_TRANSACTION_ID, transactionId);
    if (eventId != null) MDC.put(MDC_EVENT_ID, eventId);
    if (accountId != null) MDC.put(MDC_ACCOUNT_ID, accountId);
  }

  public static void clearMdc() {
    MDC.remove(MDC_TRANSACTION_ID);
    MDC.remove(MDC_EVENT_ID);
    MDC.remove(MDC_ACCOUNT_ID);
  }

  public static void tagSpan(Tracer tracer, String transactionId, String eventId, String accountId) {
    Span current = tracer != null ? tracer.currentSpan() : null;
    if (current == null) return;
    if (transactionId != null) current.tag("transaction_id", transactionId);
    if (eventId != null) current.tag("event_id", eventId);
    if (accountId != null) current.tag("account_id", accountId);
  }

  public static Optional<String> currentTraceId(Tracer tracer) {
    if (tracer == null || tracer.currentSpan() == null || tracer.currentSpan().context() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(tracer.currentSpan().context().traceId());
  }
}
