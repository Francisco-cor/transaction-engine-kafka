package com.example.transactionengine.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Centralized audit log to Loki (JSON via logback). All admin actions must go through here.
 * Retention PII 30d via Loki limits_config.retention.
 */
@Component
public class AuditLogger {

  private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

  public void log(String action, String transactionId, String requestedBy, String reason, boolean dryRun) {
    MDC.put("audit_action", action);
    MDC.put("audit_transaction_id", transactionId);
    MDC.put("audit_requested_by", requestedBy);
    try {
      AUDIT.info("audit action={} tx={} by={} reason={} dryRun={}", action, transactionId, requestedBy, reason, dryRun);
    } finally {
      MDC.remove("audit_action");
      MDC.remove("audit_transaction_id");
      MDC.remove("audit_requested_by");
    }
  }

  public void logReplay(String transactionId, String requestedBy, String reason, boolean dryRun, String status) {
    log("replay:" + status, transactionId, requestedBy, reason, dryRun);
  }

  public void logDltReplay(String outboxId, String requestedBy, String reason) {
    log("dlt_replay", outboxId, requestedBy, reason, false);
  }
}
