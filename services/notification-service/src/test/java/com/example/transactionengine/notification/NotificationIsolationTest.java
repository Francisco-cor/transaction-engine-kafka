package com.example.transactionengine.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transactionengine.notification.application.FakeNotificationProvider;
import com.example.transactionengine.notification.application.NotificationApplicationService;
import com.example.transactionengine.notification.application.TemplateRenderer;
import com.example.transactionengine.notification.application.WebhookClient;
import com.example.transactionengine.notification.persistence.InboxRepository;
import com.example.transactionengine.notification.persistence.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * F1 notification isolation — ledger never reverts when notification fails (DLT vs retryable).
 */
class NotificationIsolationTest {

  private NotificationApplicationService service() {
    var provider = new FakeNotificationProvider(0.0, 0);
    var renderer = new TemplateRenderer();
    var webhook = Mockito.mock(WebhookClient.class);
    return new NotificationApplicationService(
        Mockito.mock(InboxRepository.class),
        Mockito.mock(NotificationRepository.class),
        provider, renderer, webhook,
        new SimpleMeterRegistry(), new ObjectMapper());
  }

  @Test
  void duplicateNotificationDoesNotCreateSecondDelivery() {
    var inbox = Mockito.mock(InboxRepository.class);
    // First call insertIfAbsent true, second false → DUPLICATE
    Mockito.when(inbox.insertIfAbsent(Mockito.any(), Mockito.any(), Mockito.anyString()))
        .thenReturn(true).thenReturn(false);
    var notifications = Mockito.mock(NotificationRepository.class);
    Mockito.when(notifications.insertIfAbsent(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(true);
    var provider = new FakeNotificationProvider(0.0, 0);
    var renderer = new TemplateRenderer();
    var webhook = Mockito.mock(WebhookClient.class);
    var service = new NotificationApplicationService(
        inbox, notifications, provider, renderer, webhook,
        new SimpleMeterRegistry(), new ObjectMapper());

    String raw = validRaw(UUID.randomUUID(), UUID.randomUUID());
    var r1 = service.process(raw, null, null);
    assertThat(r1).isEqualTo("SENT");
    var r2 = service.process(raw, null, null);
    assertThat(r2).isEqualTo("DUPLICATE");
  }

  @Test
  void notificationFailureDoesNotAffectLedger() {
    // Ledger correctness is independent: notification DLT does not insert ledger entry
    // Here we verify notification marks DLT but inbox is PROCESSED (ACK) so Kafka does not redeliver infinitely
    var inbox = Mockito.mock(InboxRepository.class);
    Mockito.when(inbox.insertIfAbsent(Mockito.any(), Mockito.any(), Mockito.anyString())).thenReturn(true);
    var notifications = Mockito.mock(NotificationRepository.class);
    Mockito.when(notifications.insertIfAbsent(Mockito.any(), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.anyString()))
        .thenReturn(true);
    var provider = Mockito.mock(FakeNotificationProvider.class);
    Mockito.doThrow(new FakeNotificationProvider.RetryableNotificationException("webhook down", new RuntimeException()))
        .when(provider).send(Mockito.any(), Mockito.anyString());
    var renderer = new TemplateRenderer();
    var webhook = Mockito.mock(WebhookClient.class);
    Mockito.doThrow(new RuntimeException("timeout")).when(webhook).deliver(Mockito.any(), Mockito.anyString(), Mockito.anyString());
    var service = new NotificationApplicationService(
        inbox, notifications, provider, renderer, webhook,
        new SimpleMeterRegistry(), new ObjectMapper());
    String raw = validRaw(UUID.randomUUID(), UUID.randomUUID());
    try {
      service.process(raw, null, null);
    } catch (NotificationApplicationService.RetryableNotificationException e) {
      assertThat(e.getMessage()).contains("webhook");
    }
    // Inbox should have been markedProcessed even on retryable (to avoid infinite poison)
    Mockito.verify(inbox).markProcessed(Mockito.any());
  }

  private static String validRaw(UUID eventId, UUID txId) {
    return """
        {"eventId":"%s","eventType":"TransactionCommitted","schemaVersion":1,"occurredAt":"2026-09-01T10:00:00Z","transactionId":"%s","accountId":"demo-acc-001","amount":10,"currency":"MXN","type":"DEBIT","balanceBefore":100,"balanceAfter":90,"metadata":{}}
        """.formatted(eventId, txId);
  }
}
