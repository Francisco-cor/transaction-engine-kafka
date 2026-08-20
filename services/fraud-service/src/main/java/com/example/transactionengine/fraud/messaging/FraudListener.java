package com.example.transactionengine.fraud.messaging;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.fraud.application.FraudApplicationService;
import com.example.transactionengine.fraud.exception.PermanentFraudException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class FraudListener {

  private final FraudApplicationService fraud;
  private final ObjectMapper objectMapper;

  public FraudListener(FraudApplicationService fraud, ObjectMapper objectMapper) {
    this.fraud = fraud;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = "${fraud.input-topic:transactions.created.v1}",
      groupId = "${spring.kafka.consumer.group-id:fraud-service}",
      containerFactory = "fraudKafkaListenerContainerFactory")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    var event = parse(record.value());
    fraud.process(
        event,
        record.value(),
        headerValue(record, "traceparent"),
        headerValue(record, "correlation_id"));
    acknowledgment.acknowledge();
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
