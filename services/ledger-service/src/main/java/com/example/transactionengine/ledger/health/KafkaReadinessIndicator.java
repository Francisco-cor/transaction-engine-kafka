package com.example.transactionengine.ledger.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Readiness indicator that fails when Kafka is unreachable.
 * Liveness remains UP even if Kafka is down (pod not restarted, just not ready).
 */
@Component("kafka")
public class KafkaReadinessIndicator implements HealthIndicator {

  private final AdminClient adminClient;

  public KafkaReadinessIndicator(AdminClient adminClient) {
    this.adminClient = adminClient;
  }

  @Override
  public Health health() {
    try {
      adminClient.listTopics().names().get(java.util.concurrent.TimeUnit.SECONDS.toSeconds(2), java.util.concurrent.TimeUnit.SECONDS);
      return Health.up().withDetail("kafka", "reachable").build();
    } catch (Exception ex) {
      return Health.down(ex).withDetail("kafka", "unreachable").build();
    }
  }
}
