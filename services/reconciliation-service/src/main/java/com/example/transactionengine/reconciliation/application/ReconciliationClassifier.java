package com.example.transactionengine.reconciliation.application;

import com.example.transactionengine.reconciliation.domain.Classification;
import com.example.transactionengine.reconciliation.domain.ReconciliationSnapshot;
import com.example.transactionengine.reconciliation.domain.ReconciliationStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationClassifier {

  public Classification classify(ReconciliationSnapshot snapshot) {
    var details = baseDetails(snapshot);
    if (!"COMMITTED".equals(snapshot.transactionStatus())
        && !"REJECTED".equals(snapshot.transactionStatus())) {
      return result(
          ReconciliationStatus.PENDING, "TRANSACTION_NOT_FINAL", details);
    }
    if (snapshot.createdEventCount() == 0) {
      return result(ReconciliationStatus.MISSING, "CREATED_EVENT_MISSING", details);
    }
    if (snapshot.createdEventCount() > 1) {
      return result(ReconciliationStatus.DUPLICATE, "CREATED_EVENT_DUPLICATE", details);
    }
    if (snapshot.fraudCount() == 0) {
      return result(ReconciliationStatus.PENDING, "FRAUD_DECISION_PENDING", details);
    }
    if (snapshot.fraudCount() > 1) {
      return result(ReconciliationStatus.DUPLICATE, "FRAUD_DECISION_DUPLICATE", details);
    }
    if (snapshot.fraudEventCount() == 0) {
      return result(ReconciliationStatus.MISSING, "FRAUD_EVENT_MISSING", details);
    }
    if (snapshot.fraudEventCount() > 1) {
      return result(ReconciliationStatus.DUPLICATE, "FRAUD_EVENT_DUPLICATE", details);
    }
    if (snapshot.outcomeEventCount() == 0) {
      return result(ReconciliationStatus.MISSING, "OUTCOME_EVENT_MISSING", details);
    }
    if (snapshot.outcomeEventCount() > 1) {
      return result(ReconciliationStatus.DUPLICATE, "OUTCOME_EVENT_DUPLICATE", details);
    }

    var expectedOutcome =
        "COMMITTED".equals(snapshot.transactionStatus())
            ? "TransactionCommitted"
            : "TransactionRejected";
    if (!Objects.equals(expectedOutcome, snapshot.outcomeEventTypes())) {
      return result(ReconciliationStatus.MISMATCH, "OUTCOME_EVENT_TYPE_MISMATCH", details);
    }

    if ("COMMITTED".equals(snapshot.transactionStatus())) {
      if (snapshot.ledgerCount() == 0) {
        return result(ReconciliationStatus.MISSING, "LEDGER_ENTRY_MISSING", details);
      }
      if (snapshot.ledgerCount() > 1) {
        return result(ReconciliationStatus.DUPLICATE, "LEDGER_ENTRY_DUPLICATE", details);
      }
      if (!matchesLedger(snapshot)) {
        return result(ReconciliationStatus.MISMATCH, "LEDGER_FIELDS_MISMATCH", details);
      }
    } else if (snapshot.ledgerCount() > 0) {
      return result(ReconciliationStatus.MISMATCH, "REJECTED_HAS_LEDGER_ENTRY", details);
    }

    if (!matchesFraud(snapshot)) {
      return result(ReconciliationStatus.MISMATCH, "FRAUD_FIELDS_MISMATCH", details);
    }
    return result(ReconciliationStatus.MATCHED, "TRAJECTORY_MATCHED", details);
  }

  private static boolean matchesLedger(ReconciliationSnapshot snapshot) {
    return Objects.equals(snapshot.accountId(), snapshot.ledgerAccountId())
        && equalAmount(snapshot.amount(), snapshot.ledgerAmount())
        && Objects.equals(snapshot.currency(), snapshot.ledgerCurrency())
        && Objects.equals(snapshot.type(), snapshot.ledgerDirection());
  }

  private static boolean matchesFraud(ReconciliationSnapshot snapshot) {
    return Objects.equals(snapshot.accountId(), snapshot.fraudAccountId())
        && equalAmount(snapshot.amount(), snapshot.fraudAmount())
        && Objects.equals(snapshot.currency(), snapshot.fraudCurrency());
  }

  private static boolean equalAmount(
      java.math.BigDecimal expected, java.math.BigDecimal actual) {
    return expected != null && actual != null && expected.compareTo(actual) == 0;
  }

  private static Classification result(
      ReconciliationStatus status, String reasonCode, Map<String, Object> details) {
    return new Classification(status, reasonCode, details);
  }

  private static Map<String, Object> baseDetails(ReconciliationSnapshot snapshot) {
    var details = new LinkedHashMap<String, Object>();
    details.put("transactionStatus", snapshot.transactionStatus());
    details.put("createdEventCount", snapshot.createdEventCount());
    details.put("ledgerCount", snapshot.ledgerCount());
    details.put("fraudCount", snapshot.fraudCount());
    details.put("fraudEventCount", snapshot.fraudEventCount());
    details.put("outcomeEventCount", snapshot.outcomeEventCount());
    details.put("outcomeEventTypes", snapshot.outcomeEventTypes());
    return details;
  }
}
