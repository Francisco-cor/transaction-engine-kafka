package com.example.transactionengine.reconciliation.application;

import com.example.transactionengine.reconciliation.domain.ReconciliationResultView;
import com.example.transactionengine.reconciliation.domain.ReplayRequest;
import com.example.transactionengine.reconciliation.persistence.ReconciliationRepository;
import com.example.transactionengine.security.AuditLogger;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationApplicationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ReconciliationApplicationService.class);
  private static final TypeReference<Map<String, Object>> DETAILS_TYPE = new TypeReference<>() {};

  private final ReconciliationRepository repository;
  private final ReconciliationClassifier classifier;
  private final ObjectMapper objectMapper;
  private final AuditLogger audit;
  private final int batchSize;
  private final long pendingRetrySeconds;
  private final String replayTopic;

  public ReconciliationApplicationService(
      ReconciliationRepository repository,
      ReconciliationClassifier classifier,
      ObjectMapper objectMapper,
      AuditLogger audit,
      @Value("${reconciliation.batch-size:100}") int batchSize,
      @Value("${reconciliation.pending-retry-seconds:5}") long pendingRetrySeconds,
      @Value("${reconciliation.replay-topic:transactions.created.v1}") String replayTopic) {
    this.repository = repository;
    this.classifier = classifier;
    this.objectMapper = objectMapper;
    this.audit = audit;
    this.batchSize = Math.max(1, batchSize);
    this.pendingRetrySeconds = Math.max(1, pendingRetrySeconds);
    this.replayTopic = replayTopic;
  }

  @Scheduled(fixedDelayString = "${reconciliation.poll-delay-ms:2000}")
  @Transactional
  public void reconcileDue() {
    for (var transactionId : repository.findCandidates(batchSize)) {
      try {
        reconcileOne(transactionId);
      } catch (RuntimeException exception) {
        LOGGER.warn("Reconciliation failed for {}", transactionId, exception);
      }
    }
  }

  public ReconciliationResultView get(UUID transactionId) {
    return repository
        .findResult(transactionId)
        .map(this::toView)
        .orElseThrow(
            () ->
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "No reconciliation result for " + transactionId));
  }

  @Transactional
  public ReplayRequest replay(UUID transactionId, String reason) {
    return replay(transactionId, reason, false, "anonymous");
  }

  @Transactional
  public ReplayRequest replay(UUID transactionId, String reason, boolean dryRun, String requestedBy) {
    String effectiveReason = reason == null || reason.isBlank() ? "manual" : reason;
    if (dryRun) {
      var current = repository.findResult(transactionId).orElse(null);
      String status = current != null ? current.status() : "UNKNOWN";
      int replayCount = current != null ? current.replayCount() : 0;
      LOGGER.info(
          "Dry-run replay requested for {} by {} reason={} currentStatus={}",
          transactionId, requestedBy, effectiveReason, status);
      if (audit != null) audit.logReplay(transactionId.toString(), requestedBy, effectiveReason, true, status);
      return new ReplayRequest(transactionId, status, replayCount);
    }
    var request = repository.requestReplay(transactionId, replayTopic, effectiveReason, requestedBy, dryRun);
    LOGGER.info(
        "Replay executed for {} by {} reason={} newStatus={} replayCount={}",
        transactionId, requestedBy, effectiveReason, request.status(), request.replayCount());
    if (audit != null) audit.logReplay(transactionId.toString(), requestedBy, effectiveReason, false, request.status());
    return new ReplayRequest(request.transactionId(), request.status(), request.replayCount());
  }

  private void reconcileOne(UUID transactionId) {
    repository
        .findSnapshot(transactionId)
        .ifPresent(
            snapshot -> {
              var classification = classifier.classify(snapshot);
              try {
                repository.upsertResult(
                    transactionId,
                    classification.status().name(),
                    classification.reasonCode(),
                    objectMapper.writeValueAsString(classification.details()),
                    pendingRetrySeconds);
              } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                    "Could not serialize reconciliation details", exception);
              }
            });
  }

  private ReconciliationResultView toView(
      com.example.transactionengine.reconciliation.persistence.StoredReconciliationResult stored) {
    try {
      return new ReconciliationResultView(
          stored.transactionId(),
          com.example.transactionengine.reconciliation.domain.ReconciliationStatus.valueOf(
              stored.status()),
          stored.reasonCode(),
          objectMapper.readValue(stored.detailsJson(), DETAILS_TYPE),
          stored.attempts(),
          stored.replayCount(),
          stored.lastCheckedAt(),
          stored.nextAttemptAt(),
          stored.lastReplayAt());
    } catch (JsonProcessingException exception) {
      return new ReconciliationResultView(
          stored.transactionId(),
          com.example.transactionengine.reconciliation.domain.ReconciliationStatus.valueOf(
              stored.status()),
          stored.reasonCode(),
          Map.of("detailsError", "invalid persisted details"),
          stored.attempts(),
          stored.replayCount(),
          stored.lastCheckedAt(),
          stored.nextAttemptAt(),
          stored.lastReplayAt());
    }
  }
}
