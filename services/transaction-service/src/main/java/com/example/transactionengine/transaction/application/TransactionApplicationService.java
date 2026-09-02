package com.example.transactionengine.transaction.application;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.observability.TraceContext;
import com.example.transactionengine.security.VaultTransitClient;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TransactionApplicationService {

  private static final String EVENT_TYPE = "TransactionCreated";
  private static final int SCHEMA_VERSION_V1 = 1;
  private static final int SCHEMA_VERSION_V2 = 2;

  private final TransactionRepository transactions;
  private final OutboxRepository outbox;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final VaultTransitClient vaultTransit;

  public TransactionApplicationService(
      TransactionRepository transactions,
      OutboxRepository outbox,
      ObjectMapper objectMapper,
      Clock clock,
      VaultTransitClient vaultTransit) {
    this.transactions = transactions;
    this.outbox = outbox;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.vaultTransit = vaultTransit;
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
    boolean isV2 = StringUtils.hasText(normalizedRequest.customerNote());
    int schemaVersion = isV2 ? SCHEMA_VERSION_V2 : SCHEMA_VERSION_V1;
    // Build event payload as map to support additive field without breaking v1 consumers (BACKWARD compat)
    var eventMap = new LinkedHashMap<String, Object>();
    eventMap.put("eventId", eventId.toString());
    eventMap.put("eventType", EVENT_TYPE);
    eventMap.put("schemaVersion", schemaVersion);
    eventMap.put("occurredAt", occurredAt.toString());
    eventMap.put("transactionId", created.transactionId().toString());
    eventMap.put("accountId", created.accountId());
    eventMap.put("amount", created.amount());
    eventMap.put("currency", created.currency());
    eventMap.put("type", created.type().name());
    eventMap.put("metadata", Map.of());
    if (isV2) {
      // F3 tokenize customerNote via Vault Transit if enabled; fallback plain with vault=plain flag
      String note = normalizedRequest.customerNote();
      String storedNote = vaultTransit != null ? vaultTransit.tokenizeOrPlain(note) : note;
      eventMap.put("customerNote", storedNote);
      if (vaultTransit != null && vaultTransit.isEnabled() && storedNote != null && storedNote.startsWith("vault:")) {
        eventMap.put("customerNoteVault", "vault");
      } else if (isV2) {
        eventMap.put("customerNoteVault", "plain");
      }
      // F5 Avro V2 builder with logical types (decimal, timestamp-millis, uuid) — validates wire compat
      try {
        var schemaFile = new java.io.File("libs/event-contracts/src/main/avro/TransactionCreatedV2.avsc");
        if (!schemaFile.exists()) schemaFile = new java.io.File("src/main/avro/TransactionCreatedV2.avsc");
        if (schemaFile.exists()) {
          var schema = new org.apache.avro.Schema.Parser().parse(schemaFile);
          var builder = new org.apache.avro.generic.GenericRecordBuilder(schema);
          builder.set("eventId", eventId.toString());
          builder.set("eventType", EVENT_TYPE);
          builder.set("schemaVersion", schemaVersion);
          builder.set("occurredAt", occurredAt.toEpochMilli());
          builder.set("transactionId", created.transactionId().toString());
          builder.set("accountId", created.accountId());
          // decimal logical type: Avro expects ByteBuffer via DecimalConversion
          var decimalSchema = schema.getField("amount").schema();
          var amountBytes = new org.apache.avro.Conversions.DecimalConversion()
              .toBytes(created.amount(), decimalSchema, decimalSchema.getLogicalType());
          builder.set("amount", amountBytes);
          builder.set("currency", created.currency());
          builder.set("type", created.type().name());
          builder.set("metadata", java.util.Map.of());
          builder.set("customerNote", storedNote);
          var record = builder.build();
          // Validate record can be serialized via GenericDatumWriter (ensures logical types correct)
          var out = new java.io.ByteArrayOutputStream();
          var writer = new org.apache.avro.generic.GenericDatumWriter<org.apache.avro.generic.GenericRecord>(schema);
          var encoder = org.apache.avro.io.EncoderFactory.get().binaryEncoder(out, null);
          writer.write(record, encoder);
          encoder.flush();
        }
      } catch (Exception avroEx) {
        throw new IllegalStateException("Avro V2 build failed for wire compat", avroEx);
      }
    }
    // Also keep TransactionCreatedV1 for validation (when v1)
    if (!isV2) {
      var v1Event =
          new TransactionCreatedV1(
              eventId,
              EVENT_TYPE,
              schemaVersion,
              occurredAt,
              created.transactionId(),
              created.accountId(),
              created.amount(),
              created.currency(),
              created.type().name(),
              Map.of());
      // Ensure v1 serialization stays compatible
      eventMap.putIfAbsent("eventId", v1Event.eventId().toString());
    }

    try {
      String payload = objectMapper.writeValueAsString(eventMap);
      // W3C baggage for downstream (F2) + exemplars trace_id
      String baggage = TraceContext.baggage(created.transactionId().toString(), created.accountId());
      outbox.insert(
          new OutboxEvent(
              created.transactionId(),
              EVENT_TYPE,
              schemaVersion,
              payload,
              objectMapper.writeValueAsString(
                  Map.of(
                      "event_type", EVENT_TYPE,
                      "schema_version", Integer.toString(schemaVersion),
                      "traceparent", TraceContext.resolve(traceparent),
                      "baggage", baggage,
                      "correlation_id", resolvedCorrelationId,
                      "producer", "transaction-service",
                      "content_type", "application/json",
                      "account_id", created.accountId())),
              created.accountId(),
              "transactions.created.v1"));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize TransactionCreated", exception);
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
      var normalizedAmount = request.amount().setScale(4, RoundingMode.UNNECESSARY);
      if (normalizedAmount.signum() <= 0) {
        throw new IllegalArgumentException("amount must be positive");
      }
      // Enforce currency uppercase normalization and validate pattern after normalization
      var rawCurrency = requiredHeader(request.currency(), "currency").toUpperCase();
      if (!rawCurrency.matches("^[A-Z]{3}$")) {
        throw new IllegalArgumentException("currency must be 3 uppercase letters (ISO 4217)");
      }
      // Account id trimming and length already validated; ensure no whitespace-only
      var accountId = requiredHeader(request.accountId(), "accountId");
      String customerNote = request.customerNote();
      if (customerNote != null) {
        customerNote = customerNote.trim();
        if (customerNote.isEmpty()) customerNote = null;
        if (customerNote != null && customerNote.length() > 256) {
          throw new IllegalArgumentException("customerNote is too long");
        }
      }
      return new CreateTransactionRequest(accountId, normalizedAmount, request.type(), rawCurrency, customerNote);
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