package com.example.transactionengine.reconciliation.messaging;

import com.example.transactionengine.reconciliation.application.ReconciliationApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * CDC listener for F7 — consumes Debezium outbox events (transactions.cdc) instead of poll-only.
 * When RECONCILIATION_CDC_ENABLED=true (via debezium overlay), this listener triggers
 * reconciliation immediately on outbox CDC, complementing the @Scheduled poll fallback.
 */
@Component
@ConditionalOnProperty(name = "reconciliation.cdc.enabled", havingValue = "true")
public class CdcReconciliationListener {

  private static final Logger log = LoggerFactory.getLogger(CdcReconciliationListener.class);

  private final ReconciliationApplicationService reconciliation;
  private final ObjectMapper objectMapper;

  public CdcReconciliationListener(
      ReconciliationApplicationService reconciliation, ObjectMapper objectMapper) {
    this.reconciliation = reconciliation;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = "${reconciliation.cdc.topic:transactions.cdc}",
      groupId = "${reconciliation.cdc.group-id:reconciliation-cdc}",
      containerFactory = "kafkaListenerContainerFactory",
      autoStartup = "${reconciliation.cdc.enabled:false}")
  public void onCdc(
      @Payload String payload,
      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      @Header(KafkaHeaders.RECEIVED_KEY) String key,
      Acknowledgment acknowledgment) {
    try {
      UUID transactionId = extractTransactionId(payload, key);
      if (transactionId != null) {
        log.info("CDC reconcile trigger topic={} transactionId={}", topic, transactionId);
        try {
          reconciliation.triggerReconciliation(transactionId);
        } catch (Exception exception) {
          log.warn("CDC trigger reconciliation failed for {}", transactionId, exception);
        }
      } else {
        log.debug("CDC payload without transactionId topic={} payload={}", topic, payload);
      }
      if (acknowledgment != null) {
        acknowledgment.acknowledge();
      }
    } catch (Exception exception) {
      log.warn("CDC listener failed topic={} key={}", topic, key, exception);
      // Do not ACK on failure so Kafka will retry with backoff; DLT via ErrorHandler if configured
      throw new RuntimeException("CDC reconciliation failed", exception);
    }
  }

  private UUID extractTransactionId(String payload, String key) {
    // Try key first (Debezium route.by.field aggregate_id)
    if (key != null) {
      try {
        return UUID.fromString(key);
      } catch (IllegalArgumentException ignored) {
        // key may be JSON string
      }
    }
    try {
      JsonNode root = objectMapper.readTree(payload);
      // Debezium envelope: payload.after.aggregate_id or payload.after.transaction_id
      JsonNode after = root.path("payload").path("after");
      if (after.isMissingNode() || after.isNull()) {
        after = root.path("after");
      }
      if (after.isMissingNode()) {
        after = root;
      }
      String[] candidates = {"aggregate_id", "transaction_id", "transactionId", "id", "outbox_id"};
      for (String field : candidates) {
        JsonNode node = after.path(field);
        if (!node.isMissingNode() && !node.isNull()) {
          String text = node.asText();
          try {
            return UUID.fromString(text);
          } catch (IllegalArgumentException ignored) {
          }
        }
        // also check top-level payload
        node = root.path(field);
        if (!node.isMissingNode() && !node.isNull()) {
          try {
            return UUID.fromString(node.asText());
          } catch (IllegalArgumentException ignored) {
          }
        }
      }
      // Try headers field eventType routing: check "aggregate_id" in payload string search
      // Fallback: scan for UUID pattern
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
              .matcher(payload);
      if (matcher.find()) {
        return UUID.fromString(matcher.group());
      }
    } catch (Exception exception) {
      log.debug("Failed to parse CDC payload for transactionId", exception);
    }
    return null;
  }
}
