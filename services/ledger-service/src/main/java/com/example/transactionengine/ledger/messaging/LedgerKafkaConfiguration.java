package com.example.transactionengine.ledger.messaging;

import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
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