package com.example.transactionengine.fraud.application;

import com.example.transactionengine.contracts.FraudDecisionV1;
import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.fraud.domain.FraudDecision;
import com.example.transactionengine.fraud.domain.FraudProcessingOutcome;
import com.example.transactionengine.fraud.exception.PermanentFraudException;
import com.example.transactionengine.fraud.exception.RetryableFraudException;
import com.example.transactionengine.fraud.persistence.FraudDecisionRepository;
import com.example.transactionengine.fraud.persistence.FraudInboxRepository;
import com.example.transactionengine.fraud.persistence.FraudOutboxRepository;
import com.example.transactionengine.fraud.persistence.OutboxEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FraudApplicationService {

  private static final String EVENT_TYPE = "FraudDecision";
  private static final int SCHEMA_VERSION = 1;

  private final FraudInboxRepository inbox;
  private final FraudDecisionRepository decisions;
  private final FraudOutboxRepository outbox;
  private final FraudRuleEvaluator evaluator;
  private final FraudDecisionCache cache;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String decisionTopic;

  public FraudApplicationService(
      FraudInboxRepository inbox,
      FraudDecisionRepository decisions,
      FraudOutboxRepository outbox,
      FraudRuleEvaluator evaluator,
      FraudDecisionCache cache,
      ObjectMapper objectMapper,
      Clock clock,
      @Value("${fraud.decision-topic:transactions.fraud-decisions.v1}") String decisionTopic) {
    this.inbox = inbox;
    this.decisions = decisions;
    this.outbox = outbox;
    this.evaluator = evaluator;
    this.cache = cache;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.decisionTopic = decisionTopic;
  }

  @Transactional
  public FraudProcessingOutcome process(
      TransactionCreatedV1 event, String rawPayload, String traceparent, String correlationId) {
    validateEvent(event);
    if (!inbox.insertIfAbsent(event.eventId(), event.transactionId(), PayloadHash.sha256(rawPayload))) {
      inbox.markDuplicate(event.eventId());
      return FraudProcessingOutcome.DUPLICATE;
    }

    var transaction =
        decisions
            .findTransaction(event.transactionId())
            .orElseThrow(
                () ->
                    new RetryableFraudException(
                        "Transaction is not visible yet: " + event.transactionId()));
    if (!transaction.accountId().equals(event.accountId())
        || transaction.amount().compareTo(event.amount()) != 0
        || !transaction.currency().equals(event.currency())) {
      throw new PermanentFraudException(
          "Event does not match the persisted transaction: " + event.transactionId());
    }

    var existing = decisions.findByTransactionId(event.transactionId());
    if (existing.isPresent()) {
      cache.put(event.transactionId(), cached(existing.get()));
      inbox.markProcessed(event.eventId());
      return FraudProcessingOutcome.ALREADY_DECIDED;
    }

    var candidate =
        cache
            .get(event.transactionId())
            .map(cached -> evaluator.fromCache(event, cached))
            .orElseGet(() -> evaluator.evaluate(event));
    decisions.insertIfAbsent(candidate);
    var persisted =
        decisions
            .findByTransactionId(event.transactionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Fraud decision disappeared after insert: " + event.transactionId()));
    outbox.insert(decisionOutbox(persisted, traceparent, correlationId));
    cache.put(event.transactionId(), cached(persisted));
    inbox.markProcessed(event.eventId());
    return FraudProcessingOutcome.DECIDED;
  }

  private OutboxEvent decisionOutbox(
      FraudDecision decision, String traceparent, String correlationId) {
    var payload =
        new FraudDecisionV1(
            UUID.randomUUID(),
            EVENT_TYPE,
            SCHEMA_VERSION,
            clock.instant(),
            decision.transactionId(),
            decision.accountId(),
            decision.amount(),
            decision.currency(),
            decision.decision(),
            decision.reasonCode(),
            decision.ruleCode(),
            decision.riskScore(),
            Map.of());
    var resolvedCorrelation =
        StringUtils.hasText(correlationId) ? correlationId : UUID.randomUUID().toString();
    try {
      return new OutboxEvent(
          decision.transactionId(),
          EVENT_TYPE,
          SCHEMA_VERSION,
          objectMapper.writeValueAsString(payload),
          objectMapper.writeValueAsString(
              Map.of(
                  "event_type", EVENT_TYPE,
                  "schema_version", Integer.toString(SCHEMA_VERSION),
                  "traceparent", TraceContext.resolve(traceparent),
                  "correlation_id", resolvedCorrelation,
                  "producer", "fraud-service",
                  "content_type", "application/json",
                  "account_id", decision.accountId())),
          decision.transactionId().toString(),
          decisionTopic);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize fraud decision", exception);
    }
  }

  private static FraudDecisionCache.CachedDecision cached(FraudDecision decision) {
    return new FraudDecisionCache.CachedDecision(
        decision.decision(), decision.reasonCode(), decision.ruleCode(), decision.riskScore());
  }

  private static void validateEvent(TransactionCreatedV1 event) {
    if (event == null
        || event.eventId() == null
        || event.transactionId() == null
        || !"TransactionCreated".equals(event.eventType())
        || event.schemaVersion() != SCHEMA_VERSION
        || !StringUtils.hasText(event.accountId())
        || event.amount() == null
        || event.amount().signum() <= 0
        || !StringUtils.hasText(event.currency())) {
      throw new PermanentFraudException("Invalid TransactionCreated.v1 payload");
    }
  }
}
