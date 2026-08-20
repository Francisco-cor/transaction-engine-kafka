package com.example.transactionengine.fraud.messaging;

import com.example.transactionengine.fraud.exception.PermanentFraudException;
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
import org.springframework.util.backoff.FixedBackOff;

@EnableKafka
@Configuration
public class FraudKafkaConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> fraudKafkaListenerContainerFactory(
      ConsumerFactory<String, String> consumerFactory, CommonErrorHandler fraudErrorHandler) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setCommonErrorHandler(fraudErrorHandler);
    return factory;
  }

  @Bean
  CommonErrorHandler fraudErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${fraud.consumer.retry-interval-ms:1000}") long retryIntervalMs,
      @Value("${fraud.consumer.max-attempts:3}") long maxAttempts) {
    BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver =
        (record, exception) ->
            new TopicPartition(record.topic() + ".fraud-service.DLT", record.partition());
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, destinationResolver);
    var retries = Math.max(0, maxAttempts - 1);
    var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryIntervalMs, retries));
    errorHandler.addNotRetryableExceptions(PermanentFraudException.class);
    errorHandler.setCommitRecovered(true);
    errorHandler.setAckAfterHandle(true);
    return errorHandler;
  }
}
