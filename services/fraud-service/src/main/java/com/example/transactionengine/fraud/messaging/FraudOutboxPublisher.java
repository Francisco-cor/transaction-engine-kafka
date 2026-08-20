package com.example.transactionengine.fraud.messaging;

import com.example.transactionengine.fraud.persistence.ClaimedOutboxEvent;
import com.example.transactionengine.fraud.persistence.FraudOutboxRepository;
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
@ConditionalOnProperty(
    name = "fraud.outbox.publisher-enabled", havingValue = "true", matchIfMissing = true)
public class FraudOutboxPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(FraudOutboxPublisher.class);
  private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

  private final FraudOutboxRepository outbox;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final String topic;
  private final int batchSize;
  private final int leaseSeconds;
  private final long baseBackoffSeconds;
  private final long maxBackoffSeconds;
  private final String owner = "fraud-service-" + UUID.randomUUID();

  public FraudOutboxPublisher(
      FraudOutboxRepository outbox,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      @Value("${fraud.decision-topic:transactions.fraud-decisions.v1}") String topic,
      @Value("${fraud.outbox.batch-size:50}") int batchSize,
      @Value("${fraud.outbox.lease-seconds:30}") int leaseSeconds,
      @Value("${fraud.outbox.base-backoff-seconds:1}") long baseBackoffSeconds,
      @Value("${fraud.outbox.max-backoff-seconds:60}") long maxBackoffSeconds) {
    this.outbox = outbox;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.topic = topic;
    this.batchSize = batchSize;
    this.leaseSeconds = leaseSeconds;
    this.baseBackoffSeconds = baseBackoffSeconds;
    this.maxBackoffSeconds = maxBackoffSeconds;
  }

  @Scheduled(fixedDelayString = "${fraud.outbox.poll-delay-ms:1000}")
  public void publishDueEvents() {
    try {
      outbox.claim(batchSize, owner, leaseSeconds, topic).forEach(this::publish);
    } catch (RuntimeException exception) {
      LOGGER.warn("Fraud outbox claim failed; the next poll will retry", exception);
    }
  }

  private void publish(ClaimedOutboxEvent event) {
    try {
      var headers = objectMapper.readValue(event.headersJson(), HEADERS_TYPE);
      var record = new ProducerRecord<String, String>(event.topic(), event.partitionKey(), event.payload());
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
                  LOGGER.warn("Fraud outbox event {} failed to publish", event.outboxId(), cause);
                }
              });
    } catch (Exception exception) {
      outbox.markFailed(event.outboxId(), owner, nextBackoff(event.attempts()), summarize(exception));
      LOGGER.warn("Fraud outbox event {} could not be prepared", event.outboxId(), exception);
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
