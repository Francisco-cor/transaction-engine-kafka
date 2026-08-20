package com.example.transactionengine.fraud.application;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.example.transactionengine.fraud.domain.FraudDecision;
import com.example.transactionengine.fraud.persistence.FraudDecisionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FraudRuleEvaluator {

  private final FraudDecisionRepository decisions;
  private final Clock clock;
  private final BigDecimal amountReviewThreshold;
  private final Duration frequencyWindow;
  private final int frequencyMaxTransactions;
  private final Pattern suspiciousAccountPattern;
  private final int transactionPatternModulo;
  private final int transactionPatternRemainder;

  public FraudRuleEvaluator(
      FraudDecisionRepository decisions,
      Clock clock,
      @Value("${fraud.rules.amount-review-threshold:5000.00}") BigDecimal amountReviewThreshold,
      @Value("${fraud.rules.frequency-window-seconds:300}") long frequencyWindowSeconds,
      @Value("${fraud.rules.frequency-max-transactions:3}") int frequencyMaxTransactions,
      @Value("${fraud.rules.suspicious-account-pattern:^fraud-.*}") String suspiciousAccountPattern,
      @Value("${fraud.rules.transaction-pattern-modulo:0}") int transactionPatternModulo,
      @Value("${fraud.rules.transaction-pattern-remainder:0}") int transactionPatternRemainder) {
    this.decisions = decisions;
    this.clock = clock;
    this.amountReviewThreshold = amountReviewThreshold;
    this.frequencyWindow = Duration.ofSeconds(Math.max(1, frequencyWindowSeconds));
    this.frequencyMaxTransactions = Math.max(1, frequencyMaxTransactions);
    this.suspiciousAccountPattern = Pattern.compile(suspiciousAccountPattern);
    this.transactionPatternModulo = Math.max(0, transactionPatternModulo);
    this.transactionPatternRemainder = transactionPatternRemainder;
  }

  public FraudDecision evaluate(TransactionCreatedV1 event) {
    var now = clock.instant();
    var recentTransactions =
        decisions.countRecentTransactions(event.accountId(), now.minus(frequencyWindow));

    if (suspiciousAccountPattern.matcher(event.accountId()).matches()) {
      return decision(event, now, "BLOCK", "ACCOUNT_PATTERN", "ACCOUNT_PATTERN", 95);
    }
    if (event.amount().compareTo(amountReviewThreshold) > 0) {
      return decision(event, now, "REVIEW", "AMOUNT_THRESHOLD", "AMOUNT_THRESHOLD", 75);
    }
    if (recentTransactions > frequencyMaxTransactions) {
      return decision(event, now, "REVIEW", "FREQUENCY_THRESHOLD", "FREQUENCY_THRESHOLD", 70);
    }
    if (transactionPatternModulo > 0
        && Math.floorMod(event.transactionId().hashCode(), transactionPatternModulo)
            == transactionPatternRemainder) {
      return decision(event, now, "REVIEW", "TRANSACTION_PATTERN", "TRANSACTION_PATTERN", 60);
    }
    return decision(event, now, "PASS", "NONE", "BASELINE", 0);
  }

  private static FraudDecision decision(
      TransactionCreatedV1 event,
      Instant evaluatedAt,
      String decision,
      String reasonCode,
      String ruleCode,
      int riskScore) {
    return new FraudDecision(
        event.transactionId(),
        event.eventId(),
        event.accountId(),
        event.amount(),
        event.currency(),
        decision,
        reasonCode,
        ruleCode,
        riskScore,
        evaluatedAt);
  }

  public FraudDecision fromCache(
      TransactionCreatedV1 event, FraudDecisionCache.CachedDecision cached) {
    return new FraudDecision(
        event.transactionId(),
        event.eventId(),
        event.accountId(),
        event.amount(),
        event.currency(),
        cached.decision(),
        cached.reasonCode(),
        cached.ruleCode(),
        cached.riskScore(),
        clock.instant());
  }
}
