package com.example.transactionengine.ledger.messaging;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.ledger.application.LedgerApplicationService;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class LedgerListener {

  private final LedgerApplicationService ledger;
  private final ObjectMapper objectMapper;

  public LedgerListener(LedgerApplicationService ledger, ObjectMapper objectMapper) {
    this.ledger = ledger;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = "${ledger.input-topic:transactions.created.v1}",
      groupId = "${spring.kafka.consumer.group-id:ledger-service}",
      containerFactory = "ledgerKafkaListenerContainerFactory")
  public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
    var event = parse(record.value());
    ledger.process(
        event,
        record.value(),
        headerValue(record, "traceparent"),
        headerValue(record, "correlation_id"));
    beforeAck();
    acknowledgment.acknowledge();
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