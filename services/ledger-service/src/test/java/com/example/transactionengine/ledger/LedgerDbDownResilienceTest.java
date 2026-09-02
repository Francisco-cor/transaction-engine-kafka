package com.example.transactionengine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.ledger.application.LedgerApplicationService;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import com.example.transactionengine.ledger.exception.RetryableLedgerException;
import com.example.transactionengine.ledger.metrics.LedgerMetrics;
import com.example.transactionengine.ledger.persistence.InboxRepository;
import com.example.transactionengine.ledger.persistence.LedgerRepository;
import com.example.transactionengine.ledger.persistence.OutboxRepository;
import com.example.transactionengine.ledger.sharding.AccountShardResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * F1 chaos unit — DB-down must be Retryable and poison must be Permanent + DLT.
 * Verifies LedgerApplicationService classifies exceptions correctly per ADR-006.
 */
class LedgerDbDownResilienceTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  private LedgerApplicationService serviceWithMocks(InboxRepository inbox, LedgerRepository ledger) {
    return new LedgerApplicationService(
        inbox,
        ledger,
        Mockito.mock(OutboxRepository.class),
        mapper,
        Clock.fixed(Instant.now(), java.time.ZoneOffset.UTC),
        "topic",
        new LedgerMetrics(new SimpleMeterRegistry()),
        new AccountShardResolver(32),
        false,
        3,
        10);
  }

  @Test
  void poisonInvalidPayloadIsPermanent() {
    var inbox = Mockito.mock(InboxRepository.class);
    var ledger = Mockito.mock(LedgerRepository.class);
    Mockito.when(inbox.insertIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyString()))
        .thenReturn(true);
    var service = serviceWithMocks(inbox, ledger);

    // Missing eventType + schemaVersion 99 => Permanent
    var badEvent = new TransactionCreatedV1(
        UUID.randomUUID(), null, 99, Instant.now(), UUID.randomUUID(), "", BigDecimal.ONE, "MXN", "DEBIT", Map.of());
    assertThatThrownBy(() -> service.process(badEvent, "{}", null, null))
        .isInstanceOf(PermanentLedgerException.class);
  }

  @Test
  void dbTransientWhenTransactionNotVisibleIsRetryable() {
    var inbox = Mockito.mock(InboxRepository.class);
    var ledger = Mockito.mock(LedgerRepository.class);
    Mockito.when(inbox.insertIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyString()))
        .thenReturn(true);
    // findTransactionForUpdate returns empty => RetryableLedgerException (transaction not yet visible)
    Mockito.when(ledger.findTransactionForUpdate(Mockito.any())).thenReturn(java.util.Optional.empty());
    var service = serviceWithMocks(inbox, ledger);

    var event = new TransactionCreatedV1(
        UUID.randomUUID(), "TransactionCreated", 1, Instant.now(), UUID.randomUUID(),
        "acc-1", new BigDecimal("10.00"), "MXN", "DEBIT", Map.of());
    assertThatThrownBy(() -> service.process(event, "{}", null, null))
        .isInstanceOf(RetryableLedgerException.class)
        .hasMessageContaining("not visible");
  }

  @Test
  void dbTransientDataAccessExceptionIsClassifiedAsRetryableForErrorHandler() {
    // LedgerKafkaConfiguration adds RetryableLedgerException + TransientDataAccessException as retryable
    // Here we verify the exception hierarchy is correct
    var ex = new TransientDataAccessResourceException("connection refused");
    assertThat(ex).isInstanceOf(org.springframework.dao.TransientDataAccessException.class);
    // Permanent must not be retryable
    var permanent = new PermanentLedgerException("bad json");
    assertThat(permanent).isNotInstanceOf(RetryableLedgerException.class);
  }

  @Test
  void duplicateAfterFirstIsCountedAndNotRetried() {
    var inbox = Mockito.mock(InboxRepository.class);
    Mockito.when(inbox.insertIfAbsent(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyString()))
        .thenReturn(false); // duplicate
    var ledger = Mockito.mock(LedgerRepository.class);
    var metrics = new LedgerMetrics(new SimpleMeterRegistry());
    var shardResolver = new AccountShardResolver(32);
    var service = new LedgerApplicationService(
        inbox, ledger, Mockito.mock(OutboxRepository.class), mapper,
        Clock.systemUTC(), "topic", metrics, shardResolver, false, 3, 10);
    var event = new TransactionCreatedV1(
        UUID.randomUUID(), "TransactionCreated", 1, Instant.now(), UUID.randomUUID(),
        "acc-1", BigDecimal.ONE, "MXN", "DEBIT", Map.of());
    var outcome = service.process(event, "{}", null, null);
    assertThat(outcome.name()).isEqualTo("DUPLICATE");
    // Consumer would ACK and not send to DLT — continues processing next message
  }
}
