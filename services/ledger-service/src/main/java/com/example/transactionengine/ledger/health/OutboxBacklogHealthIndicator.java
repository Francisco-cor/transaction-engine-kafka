package com.example.transactionengine.ledger.health;

import com.example.transactionengine.ledger.persistence.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * F4 composite readiness: outbox backlog > threshold makes readiness DOWN (backpressure).
 * Prevents pod from receiving traffic when it cannot drain.
 */
@Component("outbox")
public class OutboxBacklogHealthIndicator implements HealthIndicator {

  private final OutboxRepository outbox;
  private final String topic;
  private final long threshold;

  public OutboxBacklogHealthIndicator(
      OutboxRepository outbox,
      @Value("${ledger.outcome-topic:transactions.committed.v1}") String topic,
      @Value("${ledger.outbox.backlog-threshold:100}") long threshold) {
    this.outbox = outbox;
    this.topic = topic;
    this.threshold = threshold;
  }

  @Override
  public Health health() {
    try {
      long pending = outbox.countPending(topic);
      if (pending > threshold) {
        return Health.down()
            .withDetail("outbox_pending", pending)
            .withDetail("threshold", threshold)
            .withDetail("topic", topic)
            .build();
      }
      return Health.up().withDetail("outbox_pending", pending).build();
    } catch (Exception ex) {
      return Health.up().withDetail("outbox_check", "skipped").build();
    }
  }
}
