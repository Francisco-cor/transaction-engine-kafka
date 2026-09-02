package com.example.transactionengine.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F1 Pact consumer test — ledger/fraud tolerate v2 additive field.
 * Mirrors Pact consumer: expects provider v2 JSON to be readable as V1 (BACKWARD).
 */
class PactTransactionCreatedConsumerTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void consumerV1CanReadProviderV2() throws Exception {
    var eventId = UUID.randomUUID();
    var txId = UUID.randomUUID();
    // Provider v2 payload with customerNote additive field
    var providerV2Json =
        """
        {
          "eventId":"%s",
          "eventType":"TransactionCreated",
          "schemaVersion":2,
          "occurredAt":"2026-09-01T10:00:00Z",
          "transactionId":"%s",
          "accountId":"demo-acc-001",
          "amount": 10.50,
          "currency":"MXN",
          "type":"DEBIT",
          "metadata":{},
          "customerNote":"nota v2"
        }
        """
            .formatted(eventId, txId);

    // Consumer V1 deserializes ignoring unknown => BACKWARD
    var v1 = mapper.readValue(providerV2Json, TransactionCreatedV1.class);
    assertThat(v1.eventId()).isEqualTo(eventId);
    assertThat(v1.transactionId()).isEqualTo(txId);
    assertThat(v1.schemaVersion()).isEqualTo(2);
    assertThat(v1.accountId()).isEqualTo("demo-acc-001");
    assertThat(v1.amount()).isEqualByComparingTo(new BigDecimal("10.50"));

    // Also ensure raw JSON still validates against v1 schema plus additionalProperties handling
    // v1 schema has additionalProperties false, so provider v2 would not validate as v1 — this is expected
    // Consumer contract is: deserialize with ignoreUnknown = true, not schema strict.
    JsonNode node = mapper.readTree(providerV2Json);
    assertThat(node.get("customerNote").asText()).isEqualTo("nota v2");
  }

  @Test
  void consumerV1PayloadValidatesAgainstV1Schema() throws Exception {
    var v1Json =
        """
        {
          "eventId":"%s",
          "eventType":"TransactionCreated",
          "schemaVersion":1,
          "occurredAt":"2026-09-01T10:00:00Z",
          "transactionId":"%s",
          "accountId":"acc-123",
          "amount": 100.0,
          "currency":"MXN",
          "type":"CREDIT",
          "metadata":{}
        }
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID());
    try (InputStream schemaStream =
        getClass().getResourceAsStream("/contracts/transaction-created.v1.json")) {
      // Fallback to docs/contracts path if resource not on classpath
      InputStream is =
          schemaStream != null
              ? schemaStream
              : new java.io.FileInputStream("docs/contracts/transaction-created.v1.json");
      JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
      JsonSchema schema = factory.getSchema(is);
      var errors = schema.validate(mapper.readTree(v1Json));
      assertThat(errors).isEmpty();
    }
  }

  @Test
  void providerV2WithNullCustomerNoteEqualsV1Wire() throws Exception {
    // Producer emits schemaVersion 2 only when customerNote != null, else 1 — both must be readable
    var v2NullNote =
        Map.of(
            "eventId", UUID.randomUUID().toString(),
            "eventType", "TransactionCreated",
            "schemaVersion", 1,
            "occurredAt", Instant.now().toString(),
            "transactionId", UUID.randomUUID().toString(),
            "accountId", "hot-account-001",
            "amount", new BigDecimal("10.0000"),
            "currency", "MXN",
            "type", "DEBIT",
            "metadata", Map.of());
    String json = mapper.writeValueAsString(v2NullNote);
    var read = mapper.readValue(json, TransactionCreatedV1.class);
    assertThat(read.schemaVersion()).isEqualTo(1);
  }
}
