package com.example.transactionengine.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.ledger.domain.AccountRecord;
import com.example.transactionengine.ledger.domain.PendingTransaction;
import com.example.transactionengine.ledger.domain.TransactionStatus;
import com.example.transactionengine.ledger.domain.TransactionType;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import com.example.transactionengine.ledger.metrics.LedgerMetrics;
import com.example.transactionengine.ledger.persistence.InboxRepository;
import com.example.transactionengine.ledger.persistence.LedgerRepository;
import com.example.transactionengine.ledger.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.transactionengine.ledger.sharding.AccountShardResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-20T17:00:00Z");

  @Mock private InboxRepository inbox;
  @Mock private LedgerRepository ledger;
  @Mock private OutboxRepository outbox;

  private LedgerApplicationService service;

  @BeforeEach
  void setUp() {
    var metrics = new LedgerMetrics(new SimpleMeterRegistry());
    var shardResolver = new AccountShardResolver(32);
    service =
        new LedgerApplicationService(
            inbox,
            ledger,
            outbox,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            "transactions.committed.v1",
            metrics,
            shardResolver,
            false,
            3,
            10);
  }

  @Test
  void commitsDebitWithLockedAccountAndOneOutcome() {
    var event = event(UUID.randomUUID(), "10.00", "demo-acc-001", "DEBIT");
    var transaction = transaction(event);
    when(inbox.insertIfAbsent(eq("ledger-service"), eq(event.eventId()), eq(event.transactionId()), any(String.class)))
        .thenReturn(true);
    when(ledger.findTransactionForUpdate(event.transactionId())).thenReturn(Optional.of(transaction));
    when(ledger.lockAccount("demo-acc-001"))
        .thenReturn(Optional.of(new AccountRecord("demo-acc-001", "MXN", new BigDecimal("100.00"), 4, "ACTIVE")));

    var outcome = service.process(event, "payload", "00-trace", "corr-1");

    assertThat(outcome).isEqualTo(ProcessingOutcome.COMMITTED);
    verify(ledger).insertLedgerEntry(
        event.transactionId(),
        "demo-acc-001",
        new BigDecimal("10.00"),
        TransactionType.DEBIT,
        "MXN",
        new BigDecimal("100.00"),
        new BigDecimal("90.00"));
    verify(ledger).updateAccount("demo-acc-001", new BigDecimal("90.00"));
    verify(ledger).markCommitted(event.transactionId());
    verify(outbox).insert(any());
    verify(inbox).markProcessed("ledger-service", event.eventId());
  }

  @Test
  void rejectsDebitWhenBalanceWouldBecomeNegative() {
    var event = event(UUID.randomUUID(), "101.00", "demo-acc-001", "DEBIT");
    var transaction = transaction(event);
    when(inbox.insertIfAbsent(eq("ledger-service"), eq(event.eventId()), eq(event.transactionId()), any(String.class)))
        .thenReturn(true);
    when(ledger.findTransactionForUpdate(event.transactionId())).thenReturn(Optional.of(transaction));
    when(ledger.lockAccount("demo-acc-001"))
        .thenReturn(Optional.of(new AccountRecord("demo-acc-001", "MXN", new BigDecimal("100.00"), 4, "ACTIVE")));

    var outcome = service.process(event, "payload", null, "corr-1");

    assertThat(outcome).isEqualTo(ProcessingOutcome.REJECTED);
    verify(ledger).markRejected(event.transactionId(), "INSUFFICIENT_FUNDS");
    verify(outbox).insert(any());
    verify(inbox).markProcessed("ledger-service", event.eventId());
  }

  @Test
  void duplicateEventDoesNotTouchAccountOrLedger() {
    var event = event(UUID.randomUUID(), "10.00", "demo-acc-001", "DEBIT");
    when(inbox.insertIfAbsent(eq("ledger-service"), eq(event.eventId()), eq(event.transactionId()), any(String.class)))
        .thenReturn(false);

    var outcome = service.process(event, "payload", null, null);

    assertThat(outcome).isEqualTo(ProcessingOutcome.DUPLICATE);
    verify(inbox).markDuplicate("ledger-service", event.eventId());
    verifyNoInteractions(ledger, outbox);
  }

  @Test
  void malformedEventIsPermanentAndDoesNotWriteInbox() {
    var event =
        new TransactionCreatedV1(
            UUID.randomUUID(),
            "WrongType",
            1,
            NOW,
            UUID.randomUUID(),
            "demo-acc-001",
            new BigDecimal("1.00"),
            "MXN",
            "DEBIT",
            Map.of());

    assertThatThrownBy(() -> service.process(event, "payload", null, null))
        .isInstanceOf(PermanentLedgerException.class);
    verifyNoInteractions(inbox, ledger, outbox);
  }

  private static TransactionCreatedV1 event(UUID transactionId, String amount, String accountId, String type) {
    return new TransactionCreatedV1(
        UUID.randomUUID(), "TransactionCreated", 1, NOW, transactionId, accountId,
        new BigDecimal(amount), "MXN", type, Map.of());
  }

  private static PendingTransaction transaction(TransactionCreatedV1 event) {
    return new PendingTransaction(
        event.transactionId(),
        event.accountId(),
        event.amount(),
        event.currency(),
        TransactionType.valueOf(event.type()),
        TransactionStatus.PENDING);
  }
}