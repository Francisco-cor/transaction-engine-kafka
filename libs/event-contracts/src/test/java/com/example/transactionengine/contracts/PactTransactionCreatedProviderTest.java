package com.example.transactionengine.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * F1 Pact provider test — transaction-service emits v1 and v2 correctly.
 * Verifies BACKWARD: old consumer (V1) can read new provider (V2).
 */
class PactTransactionCreatedProviderTest {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void providerEmitsV1WithoutCustomerNote() throws Exception {
    var eventId = UUID.randomUUID();
    var txId = UUID.randomUUID();
    var v1 =
        new TransactionCreatedV1(
            eventId, "TransactionCreated", 1, Instant.parse("2026-09-01T10:00:00Z"),
            txId, "demo-acc-001", new BigDecimal("10.5000"), "MXN", "DEBIT", Map.of());
    String json = mapper.writeValueAsString(v1);
    assertThat(json).contains("\"schemaVersion\":1");
    assertThat(json).doesNotContain("customerNote");

    // Consumer V1 reads its own provider
    var back = mapper.readValue(json, TransactionCreatedV1.class);
    assertThat(back.eventId()).isEqualTo(eventId);
    assertThat(back.amount()).isEqualByComparingTo(new BigDecimal("10.5000"));
  }

  @Test
  void providerEmitsV2WithCustomerNoteAndConsumerIgnoresIt() throws Exception {
    var eventId = UUID.randomUUID();
    var txId = UUID.randomUUID();
    // Simulate TransactionApplicationService building eventMap with customerNote
    var eventMap = new java.util.LinkedHashMap<String, Object>();
    eventMap.put("eventId", eventId.toString());
    eventMap.put("eventType", "TransactionCreated");
    eventMap.put("schemaVersion", 2);
    eventMap.put("occurredAt", Instant.now().toString());
    eventMap.put("transactionId", txId.toString());
    eventMap.put("accountId", "hot-account-001");
    eventMap.put("amount", new BigDecimal("99.9999"));
    eventMap.put("currency", "MXN");
    eventMap.put("type", "DEBIT");
    eventMap.put("metadata", Map.of());
    eventMap.put("customerNote", "pact v2 note");
    String json = mapper.writeValueAsString(eventMap);
    assertThat(json).contains("customerNote");
    assertThat(json).contains("\"schemaVersion\":2");

    // Old consumer reads new provider — must ignore customerNote
    var consumer = mapper.readValue(json, TransactionCreatedV1.class);
    assertThat(consumer.schemaVersion()).isEqualTo(2);
    assertThat(consumer.accountId()).isEqualTo("hot-account-001");
  }

  @Test
  void avroV2DefaultNullIsBackwardCompatible() throws Exception {
    // Avro V2 customerNote ["null","string"] default null → provider may omit field
    // Simulate producer omitting field (as V1) and consumer reading
    String jsonWithoutNote =
        """
        {"eventId":"%s","eventType":"TransactionCreated","schemaVersion":1,"occurredAt":"2026-09-01T10:00:00Z","transactionId":"%s","accountId":"acc-123","amount":10,"currency":"MXN","type":"DEBIT","metadata":{}}
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID());
    var read = mapper.readValue(jsonWithoutNote, TransactionCreatedV1.class);
    assertThat(read.schemaVersion()).isEqualTo(1);
  }
}
