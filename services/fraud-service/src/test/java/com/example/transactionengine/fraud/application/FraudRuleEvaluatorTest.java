package com.example.transactionengine.fraud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.fraud.persistence.FraudDecisionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FraudRuleEvaluatorTest {

  private static final Instant NOW = Instant.parse("2026-08-20T20:00:00Z");

  @Mock private FraudDecisionRepository decisions;

  private FraudRuleEvaluator evaluator;

  @BeforeEach
  void setUp() {
    when(decisions.countRecentTransactions(anyString(), any(Instant.class))).thenReturn(0L);
    evaluator =
        new FraudRuleEvaluator(
            decisions,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new BigDecimal("5000.00"),
            300,
            3,
            "^fraud-.*",
            0,
            0);
  }

  @Test
  void blocksSuspiciousAccount() {
    var decision = evaluator.evaluate(event("fraud-demo", new BigDecimal("10.00")));

    assertThat(decision.decision()).isEqualTo("BLOCK");
    assertThat(decision.reasonCode()).isEqualTo("ACCOUNT_PATTERN");
    assertThat(decision.riskScore()).isEqualTo(95);
  }

  @Test
  void reviewsAmountsAboveConfiguredThreshold() {
    var decision = evaluator.evaluate(event("demo-account", new BigDecimal("5000.01")));

    assertThat(decision.decision()).isEqualTo("REVIEW");
    assertThat(decision.reasonCode()).isEqualTo("AMOUNT_THRESHOLD");
    assertThat(decision.riskScore()).isEqualTo(75);
  }

  @Test
  void passesBaselineTransaction() {
    var decision = evaluator.evaluate(event("demo-account", new BigDecimal("10.00")));

    assertThat(decision.decision()).isEqualTo("PASS");
    assertThat(decision.reasonCode()).isEqualTo("NONE");
    assertThat(decision.ruleCode()).isEqualTo("BASELINE");
  }

  private static TransactionCreatedV1 event(String accountId, BigDecimal amount) {
    return new TransactionCreatedV1(
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        "TransactionCreated",
        1,
        NOW,
        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        accountId,
        amount,
        "MXN",
        "DEBIT",
        Map.of());
  }
}