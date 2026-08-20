package com.example.transactionengine.fraud.application;

import java.util.UUID;
import org.springframework.util.StringUtils;

public final class TraceContext {

  private TraceContext() {}

  public static String resolve(String traceparent) {
    return StringUtils.hasText(traceparent) ? traceparent : "00-" + UUID.randomUUID().toString().replace("-", "") + "-0000000000000001-01";
  }
}
