package com.example.transactionengine.ledger.messaging;

import com.example.transactionengine.ledger.exception.PermanentLedgerException;
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
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

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
      @Value("${ledger.consumer.max-attempts:3}") long maxAttempts) {
    BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
        (record, exception) ->
            new TopicPartition(record.topic() + ".ledger-service.DLT", record.partition());
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, destinationResolver);
    var retries = Math.max(0, maxAttempts - 1);
    var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, retries));
    errorHandler.addNotRetryableExceptions(PermanentLedgerException.class);
    errorHandler.setCommitRecovered(true);
    errorHandler.setAckAfterHandle(true);
    return errorHandler;
  }
}