package com.example.transactionengine.transaction.messaging;

import com.example.transactionengine.transaction.persistence.ClaimedOutboxEvent;
import com.example.transactionengine.transaction.persistence.OutboxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

  private final OutboxRepository outbox;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String topic;
  private final int batchSize;
  private final int leaseSeconds;
  private final long baseBackoffSeconds;
  private final long maxBackoffSeconds;
  private final String owner = "transaction-service-" + UUID.randomUUID();

  public OutboxPublisher(
      OutboxRepository outbox,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      @Value("${transaction.events.topic:transactions.created.v1}") String topic,
      @Value("${outbox.publisher.batch-size:50}") int batchSize,
      @Value("${outbox.publisher.lease-seconds:30}") int leaseSeconds,
      @Value("${outbox.publisher.base-backoff-seconds:1}") long baseBackoffSeconds,
      @Value("${outbox.publisher.max-backoff-seconds:60}") long maxBackoffSeconds) {
    this.outbox = outbox;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.topic = topic;
    this.batchSize = batchSize;
    this.leaseSeconds = leaseSeconds;
    this.baseBackoffSeconds = baseBackoffSeconds;
    this.maxBackoffSeconds = maxBackoffSeconds;
  }

  @Scheduled(fixedDelayString = "${outbox.publisher.poll-delay-ms:1000}")
  public void publishDueEvents() {
    try {
      outbox.claim(batchSize, owner, leaseSeconds).forEach(this::publish);
    } catch (RuntimeException exception) {
      LOGGER.warn("Outbox claim failed; the next poll will retry", exception);
    }
  }

  private void publish(ClaimedOutboxEvent event) {
    try {
      var headers = objectMapper.readValue(event.headersJson(), HEADERS_TYPE);
      var record = new ProducerRecord<String, String>(topic, event.partitionKey(), event.payload());
      headers.forEach(
          (name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));

      kafkaTemplate
          .send(record)
          .whenComplete(
              (result, throwable) -> {
                if (throwable == null) {
                  outbox.markPublished(event.outboxId(), owner);
                } else {
                  var cause = unwrap(throwable);
                  outbox.markFailed(
                      event.outboxId(), owner, nextBackoff(event.attempts()), summarize(cause));
                  LOGGER.warn("Outbox event {} failed to publish", event.outboxId(), cause);
                }
              });
    } catch (Exception exception) {
      outbox.markFailed(event.outboxId(), owner, nextBackoff(event.attempts()), summarize(exception));
      LOGGER.warn("Outbox event {} could not be prepared", event.outboxId(), exception);
    }
  }

  private long nextBackoff(int attempts) {
    var exponent = Math.min(attempts, 30);
    var multiplier = 1L << exponent;
    return Math.min(maxBackoffSeconds, Math.max(1, baseBackoffSeconds * multiplier));
  }

  private static Throwable unwrap(Throwable throwable) {
    if (throwable instanceof CompletionException && throwable.getCause() != null) {
      return throwable.getCause();
    }
    return throwable;
  }

  private static String summarize(Throwable throwable) {
    var suffix = throwable.getMessage() == null ? "" : ": " + throwable.getMessage();
    return throwable.getClass().getName() + suffix.substring(0, Math.min(1000, suffix.length()));
  }
}