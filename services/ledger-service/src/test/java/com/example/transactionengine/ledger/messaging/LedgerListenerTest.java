package com.example.transactionengine.ledger.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transactionengine.ledger.application.LedgerApplicationService;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class LedgerListenerTest {

  @Mock private LedgerApplicationService ledger;
  @Mock private Acknowledgment acknowledgment;

  @Test
  void acknowledgesOnlyAfterApplicationServiceReturns() {
    var payload =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"eventType\":\"TransactionCreated\",\"schemaVersion\":1,"
            + "\"occurredAt\":\"2026-08-20T17:00:00Z\",\"transactionId\":\""
            + UUID.randomUUID()
            + "\",\"accountId\":\"demo-acc-001\",\"amount\":1.00,\"currency\":\"MXN\",\"type\":\"DEBIT\",\"metadata\":{}}";
    var record = new ConsumerRecord<String, String>("transactions.created.v1", 0, 1L, "demo-acc-001", payload);
    record.headers().add("traceparent", "trace".getBytes(StandardCharsets.UTF_8));
    var listener = new LedgerListener(ledger, new ObjectMapper().findAndRegisterModules());

    listener.onMessage(record, acknowledgment);

    verify(ledger)
        .process(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(payload),
            org.mockito.ArgumentMatchers.eq("trace"),
            org.mockito.ArgumentMatchers.isNull());
    verify(acknowledgment).acknowledge();
  }

  @Test
  void crashHookAfterCommitLeavesAcknowledgmentUncalledForRedelivery() {
    var payload = "{\"eventType\":\"TransactionCreated\",\"schemaVersion\":1}";
    var record =
        new ConsumerRecord<String, String>("transactions.created.v1", 0, 1L, "key", payload);
    var listener =
        new LedgerListener(ledger, new ObjectMapper().findAndRegisterModules()) {
          @Override
          protected void beforeAck() {
            throw new IllegalStateException("simulated crash after local commit");
          }
        };
    when(
            ledger.process(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(payload),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(com.example.transactionengine.ledger.application.ProcessingOutcome.COMMITTED);

    assertThatThrownBy(() -> listener.onMessage(record, acknowledgment))
        .isInstanceOf(IllegalStateException.class);
    verify(acknowledgment, never()).acknowledge();
  }

  @Test
  void poisonPayloadIsPermanentAndIsNotAcknowledged() {
    var record =
        new ConsumerRecord<String, String>("transactions.created.v1", 0, 1L, "key", "not-json");
    var listener = new LedgerListener(ledger, new ObjectMapper().findAndRegisterModules());

    assertThatThrownBy(() -> listener.onMessage(record, acknowledgment))
        .isInstanceOf(PermanentLedgerException.class);
    verify(acknowledgment, never()).acknowledge();
  }
}