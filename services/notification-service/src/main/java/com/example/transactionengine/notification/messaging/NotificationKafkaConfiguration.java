package com.example.transactionengine.notification.messaging;

import com.example.transactionengine.notification.application.NotificationApplicationService;
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
public class NotificationKafkaConfiguration {

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerContainerFactory(
      ConsumerFactory<String, String> cf, CommonErrorHandler err) {
    var f = new ConcurrentKafkaListenerContainerFactory<String, String>();
    f.setConsumerFactory(cf);
    f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    f.setCommonErrorHandler(err);
    return f;
  }

  @Bean
  CommonErrorHandler notificationErrorHandler(
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${notification.consumer.retry-interval-ms:1000}") long retryInterval,
      @Value("${notification.consumer.max-attempts:3}") long maxAttempts) {
    BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> resolver =
        (rec, ex) -> new TopicPartition(rec.topic() + ".notification-service.DLT", rec.partition());
    var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate, resolver);
    var backOff = new ExponentialBackOff(retryInterval, 2.0);
    backOff.setMaxInterval(10000);
    var jittered = new org.springframework.util.backoff.BackOff() {
      @Override
      public org.springframework.util.backoff.BackOffExecution start() {
        var delegate = backOff.start();
        return new org.springframework.util.backoff.BackOffExecution() {
          @Override
          public long nextBackOff() {
            long interval = delegate.nextBackOff();
            if (interval == org.springframework.util.backoff.BackOffExecution.STOP) return interval;
            double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-0.2, 0.2);
            return (long) (interval * jitter);
          }
        };
      }
    };
    var handler = new DefaultErrorHandler(recoverer, jittered);
    handler.addNotRetryableExceptions(NotificationApplicationService.PermanentNotificationException.class);
    handler.addRetryableExceptions(NotificationApplicationService.RetryableNotificationException.class);
    handler.setCommitRecovered(true);
    handler.setAckAfterHandle(true);
    return handler;
  }
}
