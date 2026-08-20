package com.example.transactionengine.transaction.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transactionengine.transaction.persistence.ClaimedOutboxEvent;
import com.example.transactionengine.transaction.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

  @Mock private OutboxRepository outbox;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;

  @Test
  void marksEventPublishedOnlyAfterKafkaFutureSucceeds() {
    var event = event();
    var future = new CompletableFuture<SendResult<String, String>>();
    when(outbox.claim(eq(10), anyString(), eq(30), eq("transactions.created.v1")))
        .thenReturn(List.of(event));
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
    var publisher = publisher();

    publisher.publishDueEvents();
    verify(kafkaTemplate).send(any(ProducerRecord.class));
    assertThat(future.isDone()).isFalse();

    future.complete(null);
    verify(outbox).markPublished(eq(event.outboxId()), anyString());
  }

  @Test
  void marksEventFailedWithBackoffWhenKafkaFutureFails() {
    var event = event();
    var future = new CompletableFuture<SendResult<String, String>>();
    when(outbox.claim(eq(10), anyString(), eq(30), eq("transactions.created.v1")))
        .thenReturn(List.of(event));
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);
    var publisher = publisher();

    publisher.publishDueEvents();
    future.completeExceptionally(new IllegalStateException("broker unavailable"));

    verify(outbox).markFailed(eq(event.outboxId()), anyString(), eq(4L), anyString());
  }

  private OutboxPublisher publisher() {
    return new OutboxPublisher(outbox, kafkaTemplate, new ObjectMapper(), "transactions.created.v1", 10, 30, 4, 60);
  }

  private static ClaimedOutboxEvent event() {
    return new ClaimedOutboxEvent(
        UUID.randomUUID(),
        "TransactionCreated",
        1,
        "{\"transactionId\":\"abc\"}",
        "{\"event_type\":\"TransactionCreated\",\"schema_version\":\"1\"}",
        "demo-acc-001",
        0,
        "transactions.created.v1");
  }
}