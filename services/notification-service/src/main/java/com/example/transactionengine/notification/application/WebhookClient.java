package com.example.transactionengine.notification.application;

import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class WebhookClient {

  private static final Logger LOG = LoggerFactory.getLogger(WebhookClient.class);
  private final RestTemplate restTemplate = new RestTemplate();
  private final MeterRegistry meterRegistry;
  private final String webhookUrl;

  public WebhookClient(
      MeterRegistry meterRegistry,
      @Value("${notification.webhook.url:}") String webhookUrl) {
    this.meterRegistry = meterRegistry;
    this.webhookUrl = webhookUrl;
  }

  @Retry(name = "notification", fallbackMethod = "fallback")
  public void deliver(UUID transactionId, String accountId, String message) {
    if (webhookUrl == null || webhookUrl.isBlank()) {
      LOG.debug("Webhook URL not configured, skipping external delivery for tx {}", transactionId);
      return;
    }
    meterRegistry.counter("notifications_webhook_attempts").increment();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    Map<String, Object> payload = Map.of(
        "transactionId", transactionId.toString(),
        "accountId", accountId,
        "message", message);
    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
    restTemplate.postForEntity(webhookUrl, entity, String.class);
    meterRegistry.counter("notifications_webhook_delivered").increment();
    LOG.info("Webhook delivered for tx {}", transactionId);
  }

  public void fallback(UUID transactionId, String accountId, String message, Exception ex) {
    meterRegistry.counter("notifications_webhook_failed").increment();
    LOG.warn("Webhook fallback for tx {}: {}", transactionId, ex.getMessage());
    throw new NotificationApplicationService.RetryableNotificationException("Webhook retryable failure", ex);
  }
}
