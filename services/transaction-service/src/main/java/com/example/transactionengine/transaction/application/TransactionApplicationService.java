package com.example.transactionengine.transaction.application;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.transaction.api.CreateTransactionRequest;
import com.example.transactionengine.transaction.api.TransactionResponse;
import com.example.transactionengine.transaction.domain.TransactionRecord;
import com.example.transactionengine.transaction.exception.IdempotencyConflictException;
import com.example.transactionengine.transaction.exception.TransactionNotFoundException;
import com.example.transactionengine.transaction.persistence.NewTransaction;
import com.example.transactionengine.transaction.persistence.OutboxEvent;
import com.example.transactionengine.transaction.persistence.OutboxRepository;
import com.example.transactionengine.transaction.persistence.TransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.RoundingMode;
import java.time.Clock;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TransactionApplicationService {

  private static final String EVENT_TYPE = "TransactionCreated";
  private static final int SCHEMA_VERSION = 1;

  private final TransactionRepository transactions;
  private final OutboxRepository outbox;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public TransactionApplicationService(
      TransactionRepository transactions,
      OutboxRepository outbox,
      ObjectMapper objectMapper,
      Clock clock) {
    this.transactions = transactions;
    this.outbox = outbox;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public TransactionResponse create(
      CreateTransactionRequest request,
      String idempotencyKey,
      String idempotencyScope,
      String correlationId,
      String traceparent) {
    var key = requiredHeader(idempotencyKey, "Idempotency-Key");
    var scope = requiredHeader(idempotencyScope, "X-Tenant-Id");
    var normalizedRequest = normalize(request);
    var requestHash = RequestHash.sha256(normalizedRequest);
    var resolvedCorrelationId = resolveCorrelationId(correlationId);

    var existing = transactions.findByIdempotency(scope, key);
    if (existing.isPresent()) {
      return existingResponse(existing.get(), requestHash, resolvedCorrelationId);
    }

    var transactionId = UUID.randomUUID();
    var inserted =
        transactions.insertIfAbsent(
            new NewTransaction(
                transactionId,
                scope,
                key,
                requestHash,
                normalizedRequest.accountId(),
                normalizedRequest.amount(),
                normalizedRequest.currency(),
                normalizedRequest.type()));

    if (inserted.isEmpty()) {
      var raced =
          transactions
              .findByIdempotency(scope, key)
              .orElseThrow(
                  () -> new IllegalStateException("Idempotency insert conflict had no existing row"));
      return existingResponse(raced, requestHash, resolvedCorrelationId);
    }

    var created = inserted.get();
    var eventId = UUID.randomUUID();
    var occurredAt = clock.instant();
    var event =
        new TransactionCreatedV1(
            eventId,
            EVENT_TYPE,
            SCHEMA_VERSION,
            occurredAt,
            created.transactionId(),
            created.accountId(),
            created.amount(),
            created.currency(),
            created.type().name(),
            Map.of());

    try {
      outbox.insert(
          new OutboxEvent(
              created.transactionId(),
              EVENT_TYPE,
              SCHEMA_VERSION,
              objectMapper.writeValueAsString(event),
              objectMapper.writeValueAsString(
                  Map.of(
                      "event_type", EVENT_TYPE,
                      "schema_version", Integer.toString(SCHEMA_VERSION),
                      "traceparent", TraceContext.resolve(traceparent),
                      "correlation_id", resolvedCorrelationId,
                      "producer", "transaction-service",
                      "content_type", "application/json",
                      "account_id", created.accountId())),
              created.accountId()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize TransactionCreated.v1", exception);
    }

    return TransactionResponse.from(created, resolvedCorrelationId);
  }

  @Transactional(readOnly = true)
  public TransactionResponse get(UUID transactionId, String correlationId) {
    var transaction =
        transactions
            .findById(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    return TransactionResponse.from(transaction, resolveCorrelationId(correlationId));
  }

  private TransactionResponse existingResponse(
      TransactionRecord existing, String requestHash, String correlationId) {
    if (!existing.requestHash().equals(requestHash)) {
      throw new IdempotencyConflictException(
          "Idempotency-Key was already used with a different request body");
    }
    return TransactionResponse.from(existing, correlationId);
  }

  private static CreateTransactionRequest normalize(CreateTransactionRequest request) {
    if (request == null || request.amount() == null) {
      throw new IllegalArgumentException("amount is required");
    }
    try {
      return new CreateTransactionRequest(
          requiredHeader(request.accountId(), "accountId"),
          request.amount().setScale(4, RoundingMode.UNNECESSARY),
          request.type(),
          requiredHeader(request.currency(), "currency").toUpperCase());
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("amount supports at most four decimal places", exception);
    }
  }

  private static String requiredHeader(String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
    var trimmed = value.trim();
    if (trimmed.length() > (name.equals("Idempotency-Key") ? 256 : 128)) {
      throw new IllegalArgumentException(name + " is too long");
    }
    return trimmed;
  }

  private static String resolveCorrelationId(String correlationId) {
    return StringUtils.hasText(correlationId) ? correlationId.trim() : UUID.randomUUID().toString();
  }
}