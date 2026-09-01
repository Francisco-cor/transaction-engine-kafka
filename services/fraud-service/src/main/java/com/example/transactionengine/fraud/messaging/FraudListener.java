package com.example.transactionengine.fraud.messaging;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.fraud.application.FraudApplicationService;
import com.example.transactionengine.fraud.exception.PermanentFraudException;
import com.example.transactionengine.observability.TraceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class FraudListener {

  private final FraudApplicationService fraud;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;

  public FraudListener(
      FraudApplicationService fraud, ObjectMapper objectMapper, Tracer tracer) {
    this.fraud = fraud;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
  }

  @KafkaListener(
      topics = "${fraud.input-topic:transactions.created.v1}",
      groupId = "${spring.kafka.consumer.group-id:fraud-service}",
      containerFactory = "fraudKafkaListenerContainerFactory")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    var event = parse(record.value());
    String traceparent = TraceContext.resolve(headerValue(record, "traceparent"));
    String correlationId = headerValue(record, "correlation_id");
    MDC.put("traceparent", traceparent);
    if (correlationId != null) MDC.put("correlation_id", correlationId);
    TraceContext.putMdc(
        event.transactionId().toString(), event.eventId().toString(), event.accountId());
    TraceContext.tagSpan(tracer, event.transactionId().toString(), event.eventId().toString(), event.accountId());
    var span = tracer.currentSpan();
    if (span != null) {
      span.tag("messaging.kafka.topic", record.topic());
      span.tag("messaging.kafka.partition", String.valueOf(record.partition()));
    }
    try {
      fraud.process(event, record.value(), traceparent, correlationId);
      acknowledgment.acknowledge();
    } finally {
      MDC.remove("traceparent");
      MDC.remove("correlation_id");
      TraceContext.clearMdc();
    }
  }

  private TransactionCreatedV1 parse(String payload) {
    try {
      return objectMapper.readValue(payload, TransactionCreatedV1.class);
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new PermanentFraudException("Invalid TransactionCreated.v1 JSON", exception);
    }
  }

  private static String headerValue(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
