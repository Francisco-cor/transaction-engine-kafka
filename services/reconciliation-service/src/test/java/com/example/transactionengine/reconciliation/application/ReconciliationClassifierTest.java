package com.example.transactionengine.reconciliation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transactionengine.reconciliation.domain.ReconciliationSnapshot;
import com.example.transactionengine.reconciliation.domain.ReconciliationStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconciliationClassifierTest {

  private static final UUID TRANSACTION_ID =
      UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  private final ReconciliationClassifier classifier = new ReconciliationClassifier();

  @Test
  void matchesCompleteCommittedTrajectory() {
    var result = classifier.classify(snapshot("COMMITTED", 1, 1, 1, 1, 1, "TransactionCommitted"));

    assertThat(result.status()).isEqualTo(ReconciliationStatus.MATCHED);
    assertThat(result.reasonCode()).isEqualTo("TRAJECTORY_MATCHED");
  }

  @Test
  void reportsMissingLedgerEntry() {
    var result = classifier.classify(snapshot("COMMITTED", 1, 0, 1, 1, 1, "TransactionCommitted"));

    assertThat(result.status()).isEqualTo(ReconciliationStatus.MISSING);
    assertThat(result.reasonCode()).isEqualTo("LEDGER_ENTRY_MISSING");
  }

  @Test
  void reportsDuplicateFraudDecision() {
    var result = classifier.classify(snapshot("COMMITTED", 1, 1, 2, 2, 1, "TransactionCommitted"));

    assertThat(result.status()).isEqualTo(ReconciliationStatus.DUPLICATE);
    assertThat(result.reasonCode()).isEqualTo("FRAUD_DECISION_DUPLICATE");
  }

  @Test
  void keepsNonFinalTransactionPending() {
    var result = classifier.classify(snapshot("PENDING", 0, 0, 0, 0, 0, null));

    assertThat(result.status()).isEqualTo(ReconciliationStatus.PENDING);
    assertThat(result.reasonCode()).isEqualTo("TRANSACTION_NOT_FINAL");
  }

  private static ReconciliationSnapshot snapshot(
      String status,
      long createdEventCount,
      long ledgerCount,
      long fraudCount,
      long fraudEventCount,
      long outcomeEventCount,
      String outcomeEventTypes) {
    return new ReconciliationSnapshot(
        TRANSACTION_ID,
        status,
        "demo-account",
        new BigDecimal("10.00"),
        "MXN",
        "DEBIT",
        null,
        createdEventCount,
        ledgerCount,
        "demo-account",
        new BigDecimal("10.00"),
        "MXN",
        "DEBIT",
        fraudCount,
        "demo-account",
        new BigDecimal("10.00"),
        "MXN",
        fraudEventCount,
        outcomeEventCount,
        outcomeEventTypes);
  }
}