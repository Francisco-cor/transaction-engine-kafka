package com.example.transactionengine.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.transactionengine.notification.application.FakeNotificationProvider;
import com.example.transactionengine.notification.application.NotificationApplicationService;
import com.example.transactionengine.notification.application.TemplateRenderer;
import com.example.transactionengine.notification.application.WebhookClient;
import com.example.transactionengine.notification.persistence.InboxRepository;
import com.example.transactionengine.notification.persistence.NotificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationIdempotencyTest {

  @Mock InboxRepository inbox;
  @Mock NotificationRepository notifications;
  @Mock FakeNotificationProvider provider;
  @Mock WebhookClient webhookClient;

  MeterRegistry registry = new SimpleMeterRegistry();
  ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  NotificationApplicationService service;
  TemplateRenderer renderer = new TemplateRenderer();

  @BeforeEach
  void setUp() {
    service = new NotificationApplicationService(inbox, notifications, provider, renderer, webhookClient, registry, mapper);
  }

  @Test
  void duplicateEventDoesNotCreateSecondNotification() throws Exception {
    String payload = validCommittedPayload();
    var node = mapper.readTree(payload);
    UUID eventId = UUID.fromString(node.get("eventId").asText());
    UUID txId = UUID.fromString(node.get("transactionId").asText());

    when(inbox.insertIfAbsent(eq(eventId), eq(txId), any())).thenReturn(false);

    var result = service.process(payload, null, null);
    assertThat(result).isEqualTo("DUPLICATE");
    verify(inbox).markDuplicate(eventId);
    verify(notifications, never()).insertIfAbsent(any(), any(), any(), any(), any());
  }

  @Test
  void alreadyNotifiedIsIdempotent() throws Exception {
    String payload = validCommittedPayload();
    var node = mapper.readTree(payload);
    UUID eventId = UUID.fromString(node.get("eventId").asText());
    UUID txId = UUID.fromString(node.get("transactionId").asText());

    when(inbox.insertIfAbsent(eq(eventId), eq(txId), any())).thenReturn(true);
    when(notifications.insertIfAbsent(eq(txId), any(), any(), any(), any())).thenReturn(false);

    var result = service.process(payload, null, null);
    assertThat(result).isEqualTo("ALREADY_NOTIFIED");
    verify(inbox).markProcessed(eventId);
    verify(provider, never()).send(any(), any());
  }

  @Test
  void notificationFailureDoesNotAffectLedger() throws Exception {
    String payload = validCommittedPayload();
    var node = mapper.readTree(payload);
    UUID eventId = UUID.fromString(node.get("eventId").asText());
    UUID txId = UUID.fromString(node.get("transactionId").asText());

    when(inbox.insertIfAbsent(eq(eventId), eq(txId), any())).thenReturn(true);
    when(notifications.insertIfAbsent(eq(txId), any(), any(), any(), any())).thenReturn(true);
    doThrow(new FakeNotificationProvider.RetryableNotificationException("transient")).when(provider).send(any(), any());

    try {
      service.process(payload, null, null);
    } catch (NotificationApplicationService.RetryableNotificationException ex) {
      assertThat(ex.getMessage()).contains("transient");
    }
    // Notification marked failed but inbox processed (will be retried via DLT)
    verify(notifications).markFailed(eq(txId), any(), eq(1));
    verify(inbox).markProcessed(eventId);
    // Ledger is not touched by notification - isolation verified by not calling ledger repo
  }

  private String validCommittedPayload() {
    return """
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "TransactionCommitted",
          "schemaVersion": 1,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "amount": 10.00,
          "currency": "MXN",
          "type": "DEBIT",
          "balanceBefore": 100.00,
          "balanceAfter": 90.00,
          "metadata": {}
        }
        """;
  }
}
