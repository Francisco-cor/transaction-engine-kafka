package com.example.transactionengine.ledger.messaging;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.ledger.application.LedgerApplicationService;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import com.example.transactionengine.observability.TraceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class LedgerListener {

  private final LedgerApplicationService ledger;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;

  public LedgerListener(
      LedgerApplicationService ledger, ObjectMapper objectMapper, Tracer tracer) {
    this.ledger = ledger;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
  }

  @KafkaListener(
      topics = "${ledger.input-topic:transactions.created.v1}",
      groupId = "${spring.kafka.consumer.group-id:ledger-service}",
      containerFactory = "ledgerKafkaListenerContainerFactory")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    var event = parse(record.value());
    String traceparent = TraceContext.resolve(headerValue(record, "traceparent"));
    String correlationId = headerValue(record, "correlation_id");
    // Propagate W3C context and correlation for logs/metrics
    MDC.put("traceparent", traceparent);
    if (correlationId != null) MDC.put("correlation_id", correlationId);
    TraceContext.putMdc(
        event.transactionId().toString(), event.eventId().toString(), event.accountId());
    TraceContext.tagSpan(tracer, event.transactionId().toString(), event.eventId().toString(), event.accountId());
    // Also tag current span via tracer if auto-instrumented by Micrometer
    var span = tracer.currentSpan();
    if (span != null) {
      span.tag("messaging.kafka.topic", record.topic());
      span.tag("messaging.kafka.partition", String.valueOf(record.partition()));
      span.tag("messaging.kafka.offset", String.valueOf(record.offset()));
    }
    try {
      ledger.process(event, record.value(), traceparent, correlationId);
      beforeAck();
      acknowledgment.acknowledge();
    } finally {
      MDC.remove("traceparent");
      MDC.remove("correlation_id");
      TraceContext.clearMdc();
    }
  }

  protected void beforeAck() {}

  private TransactionCreatedV1 parse(String payload) {
    try {
      return objectMapper.readValue(payload, TransactionCreatedV1.class);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new PermanentLedgerException("Invalid TransactionCreated.v1 JSON", exception);
    }
  }

  private static String headerValue(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
  }
}