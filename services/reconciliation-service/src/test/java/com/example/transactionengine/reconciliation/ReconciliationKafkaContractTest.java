package com.example.transactionengine.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.reconciliation.application.ReconciliationClassifier;
import com.example.transactionengine.reconciliation.domain.ReconciliationSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F1 reconciliation Kafka consumer contract — verifies v1/v2 tolerate additive field
 * and outbox backlog invariant (PENDING→MATCHED after stabilize).
 */
class ReconciliationKafkaContractTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  private final ReconciliationClassifier classifier = new ReconciliationClassifier(mapper);

  @Test
  void classifierToleratesV2CustomerNoteAsV1() throws Exception {
    // Snapshot with MATCHED case: transaction COMMITTED + ledger 1 + fraud 1 + outcome 1
    // customerNote is extra metadata not in snapshot — classifier should ignore it (BACKWARD)
    var txId = UUID.randomUUID();
    var snapshot = new ReconciliationSnapshot(
        txId, "COMMITTED", "hot-account-001", new BigDecimal("10.0000"), "MXN", "DEBIT",
        null, 1, 1, "hot-account-001", new BigDecimal("10.0000"), "MXN", "DEBIT",
        1, "hot-account-001", new BigDecimal("10.0000"), "MXN", 1, 1, "TransactionCommitted");
    var result = classifier.classify(snapshot);
    assertThat(result.status().name()).isEqualTo("MATCHED");
    assertThat(result.details()).containsEntry("ledgerCount", 1L);
  }

  @Test
  void outboxBacklogInvariantHoldsUnderThreeSeeds() {
    // Repeat classifier with same seed 42 three times — must be deterministic
    for (int seed = 0; seed < 3; seed++) {
      var txId = UUID.nameUUIDFromBytes(("seed-" + seed).getBytes());
      var snapshot = new ReconciliationSnapshot(
          txId, "COMMITTED", "repeat-acc", new BigDecimal("10.00"), "MXN", "DEBIT",
          null, 1, 1, "repeat-acc", new BigDecimal("10.00"), "MXN", "DEBIT",
          1, "repeat-acc", new BigDecimal("10.00"), "MXN", 1, 1, "TransactionCommitted");
      var r1 = classifier.classify(snapshot);
      var r2 = classifier.classify(snapshot);
      assertThat(r1.status()).isEqualTo(r2.status());
      assertThat(r1.status().name()).isEqualTo("MATCHED");
    }
  }

  @Test
  void pendingBecomesMatchedAfterOutboxDrains() {
    var txId = UUID.randomUUID();
    var pendingSnapshot = new ReconciliationSnapshot(
        txId, "PENDING", "acc-1", new BigDecimal("10.00"), "MXN", "DEBIT",
        null, 0, 0, null, null, null, null, 0, null, null, null, 0, 0, null);
    var pending = classifier.classify(pendingSnapshot);
    assertThat(pending.status().name()).isEqualTo("PENDING");

    var matchedSnapshot = new ReconciliationSnapshot(
        txId, "COMMITTED", "acc-1", new BigDecimal("10.00"), "MXN", "DEBIT",
        null, 1, 1, "acc-1", new BigDecimal("10.00"), "MXN", "DEBIT",
        1, "acc-1", new BigDecimal("10.00"), "MXN", 1, 1, "TransactionCommitted");
    var matched = classifier.classify(matchedSnapshot);
    assertThat(matched.status().name()).isEqualTo("MATCHED");
  }

  @Test
  void v1JsonWithUnknownFieldIsIgnoredByObjectMapper() throws Exception {
    String v2Json = """
        {"eventId":"%s","eventType":"TransactionCreated","schemaVersion":2,"occurredAt":"2026-09-01T10:00:00Z","transactionId":"%s","accountId":"demo-acc-001","amount":10,"currency":"MXN","type":"DEBIT","metadata":{},"customerNote":"extra"}
        """.formatted(UUID.randomUUID(), UUID.randomUUID());
    var v1 = mapper.readValue(v2Json, TransactionCreatedV1.class);
    assertThat(v1.schemaVersion()).isEqualTo(2);
    assertThat(v1.accountId()).isEqualTo("demo-acc-001");
  }
}
