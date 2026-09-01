package com.example.transactionengine.notification.application;

import com.example.transactionengine.notification.persistence.InboxRepository;
import com.example.transactionengine.notification.persistence.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationApplicationService {

  private static final Logger LOG = LoggerFactory.getLogger(NotificationApplicationService.class);

  private final InboxRepository inbox;
  private final NotificationRepository notifications;
  private final FakeNotificationProvider provider;
  private final TemplateRenderer renderer;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;

  public NotificationApplicationService(
      InboxRepository inbox,
      NotificationRepository notifications,
      FakeNotificationProvider provider,
      TemplateRenderer renderer,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper) {
    this.inbox = inbox;
    this.notifications = notifications;
    this.provider = provider;
    this.renderer = renderer;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public String process(String rawPayload, String traceparent, String correlationId) {
    try {
      var node = objectMapper.readTree(rawPayload);
      UUID eventId = UUID.fromString(node.get("eventId").asText());
      UUID transactionId = UUID.fromString(node.get("transactionId").asText());
      String accountId = node.get("accountId").asText();
      BigDecimal amount = new BigDecimal(node.get("amount").asText());
      String currency = node.get("currency").asText();
      String type = node.get("type").asText();

      String payloadHash = sha256(rawPayload);
      if (!inbox.insertIfAbsent(eventId, transactionId, payloadHash)) {
        inbox.markDuplicate(eventId);
        return "DUPLICATE";
      }

      boolean isNew = notifications.insertIfAbsent(transactionId, accountId, amount, currency, type);
      if (!isNew) {
        // Already notified, idempotent
        inbox.markProcessed(eventId);
        return "ALREADY_NOTIFIED";
      }

      String message = renderer.render(transactionId, accountId, amount, currency, type);
      try {
        provider.send(transactionId, accountId);
        notifications.markSent(transactionId);
        inbox.markProcessed(eventId);
        Counter.builder("notifications_delivered").tag("status", "sent").register(meterRegistry).increment();
        Counter.builder("notifications_template_rendered").register(meterRegistry).increment();
        LOG.info("Notification delivered tx={} account={} msg={}", transactionId, accountId, message);
        return "SENT";
      } catch (Exception ex) {
        if (ex instanceof FakeNotificationProvider.RetryableNotificationException) {
          notifications.markFailed(transactionId, ex.getMessage(), 1);
          inbox.markProcessed(eventId);
          Counter.builder("notifications_delivered").tag("status", "retryable_failed").register(meterRegistry).increment();
          throw new RetryableNotificationException(ex.getMessage(), ex);
        } else {
          notifications.markDlt(transactionId, ex.getMessage());
          inbox.markProcessed(eventId);
          Counter.builder("notifications_delivered").tag("status", "dlt").register(meterRegistry).increment();
          throw new PermanentNotificationException(ex.getMessage(), ex);
        }
      }
    } catch (PermanentNotificationException | RetryableNotificationException e) {
      throw e;
    } catch (Exception e) {
      throw new PermanentNotificationException("Invalid committed event payload", e);
    }
  }

  private static String sha256(String payload) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
      var sb = new StringBuilder();
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (Exception e) {
      return "hash-error";
    }
  }

  public static class RetryableNotificationException extends RuntimeException {
    public RetryableNotificationException(String m, Throwable c) { super(m, c); }
  }
  public static class PermanentNotificationException extends RuntimeException {
    public PermanentNotificationException(String m, Throwable c) { super(m, c); }
  }
}
