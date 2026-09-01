package com.example.transactionengine.notification.application;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FakeNotificationProvider {

  private static final Logger LOG = LoggerFactory.getLogger(FakeNotificationProvider.class);
  private final double failureRate;
  private final long latencyMs;

  public FakeNotificationProvider(
      @Value("${notification.fake-provider.failure-rate:0.05}") double failureRate,
      @Value("${notification.fake-provider.latency-ms:50}") long latencyMs) {
    this.failureRate = failureRate;
    this.latencyMs = latencyMs;
  }

  public void send(UUID transactionId, String accountId) throws Exception {
    Thread.sleep(latencyMs);
    if (ThreadLocalRandom.current().nextDouble() < failureRate) {
      throw new RetryableNotificationException("Fake provider transient failure for " + transactionId);
    }
    LOG.info("Notification sent for transactionId={} accountId={}", transactionId, accountId);
  }

  public static class RetryableNotificationException extends Exception {
    public RetryableNotificationException(String msg) {
      super(msg);
    }
  }
}
