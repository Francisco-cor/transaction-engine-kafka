package com.example.transactionengine.ledger.admin;

import com.example.transactionengine.ledger.application.PayloadHash;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DltReplayService {

  private static final Logger LOG = LoggerFactory.getLogger(DltReplayService.class);

  private final NamedParameterJdbcTemplate jdbc;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final String inputTopic;

  public DltReplayService(
      NamedParameterJdbcTemplate jdbc,
      KafkaTemplate<String, String> kafkaTemplate,
      @Value("${ledger.input-topic:transactions.created.v1}") String inputTopic) {
    this.jdbc = jdbc;
    this.kafkaTemplate = kafkaTemplate;
    this.inputTopic = inputTopic;
  }

  @Transactional
  public DltReplayResult replay(
      String dltTopic, int partition, long offset, String payload, String reason, String requestedBy, boolean dryRun) {
    String payloadHash = PayloadHash.sha256(payload != null ? payload : "");
    // Audit first (idempotent via unique constraint)
    try {
      jdbc.update(
          """
          INSERT INTO transaction_schema.dlt_replay_audit (
              topic, partition_id, offset_value, consumer_group, replay_reason, requested_by, dry_run, payload_hash
          ) VALUES (:topic, :partition, :offset, 'ledger-service', :reason, :requestedBy, :dryRun, :hash)
          ON CONFLICT (topic, partition_id, offset_value, consumer_group) DO NOTHING
          """,
          new MapSqlParameterSource()
              .addValue("topic", dltTopic)
              .addValue("partition", partition)
              .addValue("offset", offset)
              .addValue("reason", reason)
              .addValue("requestedBy", requestedBy)
              .addValue("dryRun", dryRun)
              .addValue("hash", payloadHash));
    } catch (Exception ex) {
      LOG.warn("DLT replay audit insert failed for {}-{}-{} dryRun={}", dltTopic, partition, offset, dryRun, ex);
    }

    if (dryRun) {
      LOG.info("DLT dry-run replay: topic={} partition={} offset={} by={} reason={} hash={}", dltTopic, partition, offset, requestedBy, reason, payloadHash);
      return new DltReplayResult(dltTopic, partition, offset, "DRY_RUN", payloadHash);
    }

    // Re-publish to original input topic; consumer dedup via inbox will handle duplicates
    String key = extractAccountId(payload);
    kafkaTemplate.send(inputTopic, key, payload);
    LOG.info("DLT replay published: topic={} partition={} offset={} -> {} key={} by={} reason={}", dltTopic, partition, offset, inputTopic, key, requestedBy, reason);
    return new DltReplayResult(dltTopic, partition, offset, "REPLAYED", payloadHash);
  }

  private String extractAccountId(String payload) {
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
      var account = node.get("accountId");
      return account != null ? account.asText() : UUID.randomUUID().toString();
    } catch (Exception e) {
      return UUID.randomUUID().toString();
    }
  }

  public record DltReplayResult(String topic, int partition, long offset, String status, String payloadHash) {}
}
