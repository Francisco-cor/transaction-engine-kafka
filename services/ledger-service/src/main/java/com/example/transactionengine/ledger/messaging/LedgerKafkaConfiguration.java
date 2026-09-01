package com.example.transactionengine.ledger.messaging;

import com.example.transactionengine.ledger.application.PayloadHash;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@EnableKafka
@Configuration
public class LedgerKafkaConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> ledgerKafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory, CommonErrorHandler ledgerErrorHandler) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(ledgerErrorHandler);
    return factory;
  }

  @Bean
  CommonErrorHandler ledgerErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${ledger.consumer.retry-interval-ms:1000}") long retryIntervalMs,
      @Value("${ledger.consumer.max-attempts:3}") long maxAttempts,
      @Value("${ledger.consumer.backoff-multiplier:2.0}") double multiplier,
      @Value("${ledger.consumer.backoff-max-interval-ms:10000}") long maxIntervalMs,
      @Value("${ledger.consumer.jitter-factor:0.2}") double jitterFactor) {
    BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
        (record, exception) ->
            new TopicPartition(record.topic() + ".ledger-service.DLT", record.partition());
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, destinationResolver);
    // Enrich DLT headers with failure metadata (ADR-006)
    recoverer.setHeadersFunction(
        (record, ex) -> {
          var headers = new RecordHeaders();
          String payload = record.value() != null ? record.value().toString() : "";
          String payloadHash = PayloadHash.sha256(payload);
          headers.add("exception_class", ex.getClass().getName().getBytes(StandardCharsets.UTF_8));
          String msg = ex.getMessage() != null ? ex.getMessage().substring(0, Math.min(1000, ex.getMessage().length())) : "";
          headers.add("exception_message", msg.getBytes(StandardCharsets.UTF_8));
          headers.add("payload_hash", payloadHash.getBytes(StandardCharsets.UTF_8));
          headers.add("consumer_group", "ledger-service".getBytes(StandardCharsets.UTF_8));
          headers.add("first_failure_at", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
          headers.add("last_failure_at", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
          headers.add("failure_count", String.valueOf(maxAttempts).getBytes(StandardCharsets.UTF_8));
          // Preserve original traceparent if present
          var trace = record.headers().lastHeader("traceparent");
          if (trace != null) headers.add("traceparent", trace.value());
          var corr = record.headers().lastHeader("correlation_id");
          if (corr != null) headers.add("correlation_id", corr.value());
          return headers;
        });
    var backOff = new ExponentialBackOff(retryIntervalMs, multiplier);
    backOff.setMaxInterval(maxIntervalMs);
    backOff.setMaxElapsedTime(maxIntervalMs * Math.max(1, maxAttempts));
    // Wrap with jitter:  +/- jitterFactor * interval
    var jitteredBackOff =
        new org.springframework.util.backoff.BackOff() {
          @Override
          public org.springframework.util.backoff.BackOffExecution start() {
            var delegate = backOff.start();
            return new org.springframework.util.backoff.BackOffExecution() {
              @Override
              public long nextBackOff() {
                long interval = delegate.nextBackOff();
                if (interval == org.springframework.util.backoff.BackOffExecution.STOP) return interval;
                double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-jitterFactor, jitterFactor);
                return (long) (interval * jitter);
              }
            };
          }
        };
    var errorHandler = new DefaultErrorHandler(recoverer, jitteredBackOff);
    errorHandler.addNotRetryableExceptions(PermanentLedgerException.class);
    // Retryable: DB transient, Kafka transient
    errorHandler.addRetryableExceptions(
        com.example.transactionengine.ledger.exception.RetryableLedgerException.class,
        org.springframework.dao.TransientDataAccessException.class);
    errorHandler.setCommitRecovered(true);
    errorHandler.setAckAfterHandle(true);
    return errorHandler;
  }
}