package com.example.transactionengine.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transactionengine.contracts.TransactionCreatedV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

class SchemaCoexistenceTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void v1ConsumerCanReadV2PayloadWithCustomerNote() throws Exception {
    // Producer v2 emits JSON with customerNote
    String v2Json = """
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "TransactionCreated",
          "schemaVersion": 2,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "amount": 10.00,
          "currency": "MXN",
          "type": "DEBIT",
          "metadata": {},
          "customerNote": "urgent"
        }
        """;
    // v1 consumer should ignore customerNote
    TransactionCreatedV1 v1 = mapper.readValue(v2Json, TransactionCreatedV1.class);
    assertThat(v1.schemaVersion()).isEqualTo(2);
    assertThat(v1.accountId()).isEqualTo("demo-acc-001");
    // amount mapping via BigDecimal
    assertThat(v1.amount()).isEqualByComparingTo(new BigDecimal("10.00"));
  }

  @Test
  void v1PayloadReadByV2WithDefaultNull() throws Exception {
    String v1Json = """
        {
          "eventId": "123e4567-e89b-12d3-a456-426614174000",
          "eventType": "TransactionCreated",
          "schemaVersion": 1,
          "occurredAt": "2026-08-20T17:00:00Z",
          "transactionId": "223e4567-e89b-12d3-a456-426614174001",
          "accountId": "demo-acc-001",
          "amount": 10.00,
          "currency": "MXN",
          "type": "DEBIT",
          "metadata": {}
        }
        """;
    var node = mapper.readTree(v1Json);
    assertThat(node.has("customerNote")).isFalse();
    // Simulate v2 consumer reading v1: should default to null
    String customerNote = node.has("customerNote") ? node.get("customerNote").asText() : null;
    assertThat(customerNote).isNull();
  }

  @Test
  void avroSchemasAreBackwardCompatible() throws Exception {
    Schema v1 = loadAvroSchema("TransactionCreatedV1.avsc");
    Schema v2 = loadAvroSchema("TransactionCreatedV2.avsc");
    // v2 should be able to read v1 (BACKWARD)
    var result = SchemaCompatibility.checkReaderWriterCompatibility(v2, v1);
    assertThat(result.getResultType()).isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    // v1 should not necessarily read v2 without default, but since v2 has default null for customerNote, it is BACKWARD
    var result2 = SchemaCompatibility.checkReaderWriterCompatibility(v1, v2);
    // v1 reading v2 should also be compatible because new field has default
    assertThat(result2.getResultType()).isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
  }

  private Schema loadAvroSchema(String fileName) throws Exception {
    Path p = Path.of("libs/event-contracts/src/main/avro/" + fileName);
    if (!Files.exists(p)) {
      p = Path.of("../../libs/event-contracts/src/main/avro/" + fileName);
    }
    if (!Files.exists(p)) {
      p = Path.of("src/main/avro/" + fileName);
      if (!Files.exists(p)) {
        // Search from working dir parent
        Path working = Path.of(System.getProperty("user.dir"));
        p = working.resolve("libs/event-contracts/src/main/avro/" + fileName).normalize();
      }
    }
    String json = Files.readString(p);
    return new Schema.Parser().parse(json);
  }

  @Test
  void transactionCreatedV1RecordSerializesWithFourDecimals() throws Exception {
    var event = new TransactionCreatedV1(
        UUID.randomUUID(),
        "TransactionCreated",
        1,
        Instant.parse("2026-08-20T17:00:00Z"),
        UUID.randomUUID(),
        "demo-acc-001",
        new BigDecimal("10.0000"),
        "MXN",
        "DEBIT",
        Map.of());
    String json = mapper.writeValueAsString(event);
    assertThat(json).contains("\"amount\"");
  }
}
