package com.example.transactionengine.ledger.application;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceContext {

  private static final Pattern W3C_TRACEPARENT =
      Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[1-3]$");

  private TraceContext() {}

  public static String resolve(String candidate) {
    if (candidate != null && W3C_TRACEPARENT.matcher(candidate).matches()) {
      return candidate;
    }
    var traceId = uuidWithoutDashes() + uuidWithoutDashes();
    var spanId = uuidWithoutDashes().substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }

  private static String uuidWithoutDashes() {
    return UUID.randomUUID().toString().replace("-", "");
  }
}