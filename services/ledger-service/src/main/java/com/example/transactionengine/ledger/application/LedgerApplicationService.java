package com.example.transactionengine.ledger.application;

import com.example.transactionengine.contracts.TransactionCommittedV1;
import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.contracts.TransactionRejectedV1;
import com.example.transactionengine.ledger.domain.AccountRecord;
import com.example.transactionengine.ledger.domain.LedgerReasonCode;
import com.example.transactionengine.ledger.domain.PendingTransaction;
import com.example.transactionengine.ledger.domain.TransactionStatus;
import com.example.transactionengine.ledger.domain.TransactionType;
import com.example.transactionengine.ledger.exception.PermanentLedgerException;
import com.example.transactionengine.ledger.exception.RetryableLedgerException;
import com.example.transactionengine.ledger.persistence.InboxRepository;
import com.example.transactionengine.ledger.persistence.LedgerRepository;
import com.example.transactionengine.ledger.persistence.OutboxEvent;
import com.example.transactionengine.ledger.persistence.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LedgerApplicationService {

  private static final String CONSUMER_NAME = "ledger-service";
  private static final String CREATED_EVENT = "TransactionCreated";
  private static final String COMMITTED_EVENT = "TransactionCommitted";
  private static final String REJECTED_EVENT = "TransactionRejected";
  private static final int SCHEMA_VERSION = 1;

  private final InboxRepository inbox;
  private final LedgerRepository ledger;
  private final OutboxRepository outbox;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String outcomeTopic;

  public LedgerApplicationService(
      InboxRepository inbox,
      LedgerRepository ledger,
      OutboxRepository outbox,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${ledger.outcome-topic:transactions.committed.v1}") String outcomeTopic) {
    this.inbox = inbox;
    this.ledger = ledger;
    this.outbox = outbox;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.outcomeTopic = outcomeTopic;
  }

  @Transactional
  public ProcessingOutcome process(
      TransactionCreatedV1 event, String rawPayload, String traceparent, String correlationId) {
    validateEvent(event);
    var payloadHash = PayloadHash.sha256(rawPayload);
    if (!inbox.insertIfAbsent(CONSUMER_NAME, event.eventId(), event.transactionId(), payloadHash)) {
      inbox.markDuplicate(CONSUMER_NAME, event.eventId());
      return ProcessingOutcome.DUPLICATE;
    }

    var transaction =
        ledger
            .findTransactionForUpdate(event.transactionId())
            .orElseThrow(
                () ->
                    new RetryableLedgerException(
                        "Transaction is not visible yet: " + event.transactionId()));
    validateMatchesPersistedTransaction(transaction, event);

    if (transaction.status() != TransactionStatus.PENDING) {
      inbox.markProcessed(CONSUMER_NAME, event.eventId());
      return ProcessingOutcome.ALREADY_FINAL;
    }

    var account = ledger.lockAccount(event.accountId());
    if (account.isEmpty()) {
      return reject(
          transaction,
          event,
          LedgerReasonCode.ACCOUNT_NOT_FOUND,
          traceparent,
          correlationId);
    }
    var accountRecord = account.get();
    var statusReason = accountStatusReason(accountRecord);
    if (statusReason != null) {
      return reject(transaction, event, statusReason, traceparent, correlationId);
    }
    if (!accountRecord.currency().equals(event.currency())) {
      return reject(
          transaction,
          event,
          LedgerReasonCode.CURRENCY_MISMATCH,
          traceparent,
          correlationId);
    }

    var balanceBefore = accountRecord.availableBalance();
    var balanceAfter = calculateBalance(balanceBefore, event.amount(), event.type());
    if (balanceAfter.signum() < 0) {
      return reject(
          transaction,
          event,
          LedgerReasonCode.INSUFFICIENT_FUNDS,
          traceparent,
          correlationId);
    }

    ledger.insertLedgerEntry(
        transaction.transactionId(),
        transaction.accountId(),
        transaction.amount(),
        transaction.type(),
        transaction.currency(),
        balanceBefore,
        balanceAfter);
    ledger.updateAccount(transaction.accountId(), balanceAfter);
    ledger.markCommitted(transaction.transactionId());
    outbox.insert(
        committedOutbox(
            transaction, event, balanceBefore, balanceAfter, traceparent, correlationId));
    inbox.markProcessed(CONSUMER_NAME, event.eventId());
    return ProcessingOutcome.COMMITTED;
  }

  private ProcessingOutcome reject(
      PendingTransaction transaction,
      TransactionCreatedV1 event,
      LedgerReasonCode reason,
      String traceparent,
      String correlationId) {
    ledger.markRejected(transaction.transactionId(), reason.name());
    outbox.insert(rejectedOutbox(transaction, event, reason, traceparent, correlationId));
    inbox.markProcessed(CONSUMER_NAME, event.eventId());
    return ProcessingOutcome.REJECTED;
  }

  private OutboxEvent committedOutbox(
      PendingTransaction transaction,
      TransactionCreatedV1 event,
      BigDecimal balanceBefore,
      BigDecimal balanceAfter,
      String traceparent,
      String correlationId) {
    var payload =
        new TransactionCommittedV1(
            UUID.randomUUID(),
            COMMITTED_EVENT,
            SCHEMA_VERSION,
            clock.instant(),
            transaction.transactionId(),
            transaction.accountId(),
            transaction.amount(),
            transaction.currency(),
            transaction.type().name(),
            balanceBefore,
            balanceAfter,
            Map.of());
    return outcomeOutbox(transaction, payload, COMMITTED_EVENT, traceparent, correlationId);
  }

  private OutboxEvent rejectedOutbox(
      PendingTransaction transaction,
      TransactionCreatedV1 event,
      LedgerReasonCode reason,
      String traceparent,
      String correlationId) {
    var payload =
        new TransactionRejectedV1(
            UUID.randomUUID(),
            REJECTED_EVENT,
            SCHEMA_VERSION,
            clock.instant(),
            transaction.transactionId(),
            transaction.accountId(),
            transaction.amount(),
            transaction.currency(),
            transaction.type().name(),
            reason.name(),
            Map.of());
    return outcomeOutbox(transaction, payload, REJECTED_EVENT, traceparent, correlationId);
  }

  private OutboxEvent outcomeOutbox(
      PendingTransaction transaction,
      Object payload,
      String eventType,
      String traceparent,
      String correlationId) {
    var resolvedCorrelationId =
        StringUtils.hasText(correlationId) ? correlationId : UUID.randomUUID().toString();
    try {
      return new OutboxEvent(
          transaction.transactionId(),
          eventType,
          SCHEMA_VERSION,
          objectMapper.writeValueAsString(payload),
          objectMapper.writeValueAsString(
              Map.of(
                  "event_type", eventType,
                  "schema_version", Integer.toString(SCHEMA_VERSION),
                  "traceparent", TraceContext.resolve(traceparent),
                  "correlation_id", resolvedCorrelationId,
                  "producer", "ledger-service",
                  "content_type", "application/json",
                  "account_id", transaction.accountId())),
          transaction.transactionId().toString(),
          outcomeTopic);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize ledger outcome", exception);
    }
  }

  private static void validateEvent(TransactionCreatedV1 event) {
    if (event == null
        || event.eventId() == null
        || event.transactionId() == null
        || event.eventType() == null
        || !CREATED_EVENT.equals(event.eventType())
        || (event.schemaVersion() != 1 && event.schemaVersion() != 2)
        || !StringUtils.hasText(event.accountId())
        || !StringUtils.hasText(event.currency())
        || event.amount() == null
        || event.amount().signum() <= 0
        || !StringUtils.hasText(event.type())) {
      throw new PermanentLedgerException("Invalid TransactionCreated payload (expect schemaVersion 1 or 2)");
    }
    try {
      TransactionType.valueOf(event.type());
    } catch (IllegalArgumentException exception) {
      throw new PermanentLedgerException("Unsupported transaction type", exception);
    }
  }

  private static void validateMatchesPersistedTransaction(
      PendingTransaction transaction, TransactionCreatedV1 event) {
    if (!transaction.accountId().equals(event.accountId())
        || transaction.amount().compareTo(event.amount()) != 0
        || !transaction.currency().equals(event.currency())
        || !transaction.type().name().equals(event.type())) {
      throw new PermanentLedgerException(
          "Event does not match the persisted transaction: " + transaction.transactionId());
    }
  }

  private static LedgerReasonCode accountStatusReason(AccountRecord account) {
    return switch (account.status()) {
      case "ACTIVE" -> null;
      case "BLOCKED" -> LedgerReasonCode.ACCOUNT_BLOCKED;
      case "CLOSED" -> LedgerReasonCode.ACCOUNT_CLOSED;
      default -> LedgerReasonCode.ACCOUNT_BLOCKED;
    };
  }

  private static BigDecimal calculateBalance(
      BigDecimal balanceBefore, BigDecimal amount, String type) {
    return TransactionType.DEBIT.name().equals(type)
        ? balanceBefore.subtract(amount)
        : balanceBefore.add(amount);
  }
}