package com.example.transactionengine.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.transactionengine.transaction.api.CreateTransactionRequest;
import com.example.transactionengine.transaction.domain.TransactionRecord;
import com.example.transactionengine.transaction.domain.TransactionStatus;
import com.example.transactionengine.transaction.domain.TransactionType;
import com.example.transactionengine.transaction.exception.IdempotencyConflictException;
import com.example.transactionengine.transaction.persistence.NewTransaction;
import com.example.transactionengine.transaction.persistence.OutboxEvent;
import com.example.transactionengine.transaction.persistence.OutboxRepository;
import com.example.transactionengine.transaction.persistence.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class TransactionApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-20T17:00:00Z");

  @Mock private TransactionRepository transactions;
  @Mock private OutboxRepository outbox;

  private TransactionApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new TransactionApplicationService(
            transactions,
            outbox,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void sameIdempotencyKeyReturnsOriginalTransactionWithoutNewOutbox() {
    var request = request();
    var transaction = transaction(RequestHash.sha256(request));
    when(transactions.findByIdempotency("tenant-a", "key-1"))
        .thenReturn(Optional.of(transaction));

    var response = service.create(request, "key-1", "tenant-a", "corr-1", null);

    assertThat(response.transactionId()).isEqualTo(transaction.transactionId());
    assertThat(response.status()).isEqualTo(TransactionStatus.PENDING);
    verifyNoInteractions(outbox);
  }

  @Test
  void sameIdempotencyKeyWithDifferentBodyReturnsConflict() {
    var request = request();
    when(transactions.findByIdempotency("tenant-a", "key-1"))
        .thenReturn(Optional.of(transaction("different-hash")));

    assertThatThrownBy(() -> service.create(request, "key-1", "tenant-a", "corr-1", null))
        .isInstanceOf(IdempotencyConflictException.class);
    verifyNoInteractions(outbox);
  }

  @Test
  void newTransactionInsertsOutboxUsingSameBusinessIdentifiers() {
    var request = request();
    when(transactions.findByIdempotency("tenant-a", "key-1")).thenReturn(Optional.empty());
    when(transactions.insertIfAbsent(any(NewTransaction.class)))
        .thenAnswer(invocation -> Optional.of(transaction(RequestHash.sha256(request))));

    var response = service.create(request, "key-1", "tenant-a", "corr-1", null);

    var eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    verify(outbox).insert(eventCaptor.capture());
    var event = eventCaptor.getValue();
    assertThat(event.aggregateId()).isEqualTo(response.transactionId());
    assertThat(event.partitionKey()).isEqualTo(request.accountId());
    assertThat(event.payload()).contains(response.transactionId().toString());
    assertThat(event.headersJson()).contains("TransactionCreated", "corr-1", "account_id");
  }

  private static CreateTransactionRequest request() {
    return new CreateTransactionRequest("demo-acc-001", new BigDecimal("483.21"), TransactionType.DEBIT, "MXN");
  }

  private static TransactionRecord transaction(String requestHash) {
    return new TransactionRecord(
        UUID.randomUUID(),
        "tenant-a",
        "key-1",
        requestHash,
        "demo-acc-001",
        new BigDecimal("483.2100"),
        "MXN",
        TransactionType.DEBIT,
        TransactionStatus.PENDING,
        null,
        NOW,
        NOW);
  }
}