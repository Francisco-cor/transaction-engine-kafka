package com.example.transactionengine.notification.messaging;

import com.example.transactionengine.notification.application.NotificationApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import com.example.transactionengine.observability.TraceContext;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

@Component
public class NotificationListener {

  private final NotificationApplicationService service;
  private final ObjectMapper objectMapper;
  private final Tracer tracer;

  public NotificationListener(NotificationApplicationService service, ObjectMapper objectMapper, Tracer tracer) {
    this.service = service;
    this.objectMapper = objectMapper;
    this.tracer = tracer;
  }

  @KafkaListener(topics = "${notification.input-topic:transactions.committed.v1}", groupId = "${spring.kafka.consumer.group-id:notification-service}", containerFactory = "notificationKafkaListenerContainerFactory")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
    String traceparent = header(record, "traceparent");
    String correlationId = header(record, "correlation_id");
    traceparent = TraceContext.resolve(traceparent);
    MDC.put("traceparent", traceparent);
    if (correlationId != null) MDC.put("correlation_id", correlationId);
    try {
      // Basic JSON validation
      objectMapper.readTree(record.value());
      TraceContext.tagSpan(tracer, null, null, record.key());
      service.process(record.value(), traceparent, correlationId);
      ack.acknowledge();
    } catch (Exception ex) {
      if (ex instanceof NotificationApplicationService.PermanentNotificationException) {
        // Will be sent to DLT via error handler
        throw (RuntimeException) ex;
      }
      if (ex instanceof NotificationApplicationService.RetryableNotificationException) {
        throw (RuntimeException) ex;
      }
      throw new RuntimeException(ex);
    } finally {
      MDC.remove("traceparent");
      MDC.remove("correlation_id");
    }
  }

  private static String header(ConsumerRecord<String, String> r, String name) {
    Header h = r.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
  }
}
