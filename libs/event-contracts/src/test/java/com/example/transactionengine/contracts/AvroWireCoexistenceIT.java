package com.example.transactionengine.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

/**
 * F5 Avro wire coexistence IT — HybridAvroJsonDeserializer handles both JSON (current) and Avro binary.
 * Also verifies producer Avro builder with logical types (decimal, timestamp-millis, uuid).
 */
class AvroWireCoexistenceIT {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void hybridDeserializerHandlesJsonV1AndV2() {
    var hybrid = new HybridAvroJsonDeserializer();
    String v1Json = """
        {"eventId":"%s","eventType":"TransactionCreated","schemaVersion":1,"occurredAt":"2026-09-01T10:00:00Z","transactionId":"%s","accountId":"demo-acc-001","amount":10,"currency":"MXN","type":"DEBIT","metadata":{}}
        """.formatted(UUID.randomUUID(), UUID.randomUUID());
    String v2Json = """
        {"eventId":"%s","eventType":"TransactionCreated","schemaVersion":2,"occurredAt":"2026-09-01T10:00:00Z","transactionId":"%s","accountId":"hot-account-001","amount":99.9999,"currency":"MXN","type":"DEBIT","metadata":{},"customerNote":"wire v2"}
        """.formatted(UUID.randomUUID(), UUID.randomUUID());

    String out1 = hybrid.deserialize("transactions.created.v1", v1Json.getBytes(StandardCharsets.UTF_8));
    String out2 = hybrid.deserialize("transactions.created.v1", v2Json.getBytes(StandardCharsets.UTF_8));

    assertThat(out1).contains("demo-acc-001");
    assertThat(out2).contains("customerNote");
    assertThat(out2).contains("hot-account-001");
  }

  @Test
  void avroV2BuilderWithLogicalTypesSerializesAndHybridReadsBack() throws Exception {
    File schemaFile = new File("src/main/avro/TransactionCreatedV2.avsc");
    if (!schemaFile.exists()) schemaFile = new File("libs/event-contracts/src/main/avro/TransactionCreatedV2.avsc");
    Schema schema = new Schema.Parser().parse(schemaFile);

    var eventId = UUID.randomUUID().toString();
    var txId = UUID.randomUUID().toString();
    var now = Instant.now();

    var builder = new GenericRecordBuilder(schema);
    builder.set("eventId", eventId);
    builder.set("eventType", "TransactionCreated");
    builder.set("schemaVersion", 2);
    builder.set("occurredAt", now.toEpochMilli());
    builder.set("transactionId", txId);
    builder.set("accountId", "hot-account-001");
    var amount = new java.math.BigDecimal("123.4567");
    var decimalSchema = schema.getField("amount").schema();
    var bytes = new org.apache.avro.Conversions.DecimalConversion()
        .toBytes(amount, decimalSchema, decimalSchema.getLogicalType());
    builder.set("amount", bytes);
    builder.set("currency", "MXN");
    builder.set("type", "DEBIT");
    builder.set("metadata", Map.of());
    builder.set("customerNote", "avro wire test");

    var record = builder.build();
    // Write with Confluent header (magic 0 + 4 bytes schema id fake)
    var out = new java.io.ByteArrayOutputStream();
    out.write(0);
    out.write(new byte[]{0,0,0,1}); // fake schema id 1
    var writer = new GenericDatumWriter<org.apache.avro.generic.GenericRecord>(schema);
    var encoder = EncoderFactory.get().binaryEncoder(out, null);
    writer.write(record, encoder);
    encoder.flush();
    byte[] avroBytes = out.toByteArray();

    var hybrid = new HybridAvroJsonDeserializer();
    String json = hybrid.deserialize("transactions.created.v1", avroBytes);
    assertThat(json).contains("hot-account-001");
    assertThat(json).contains("avro wire test");
    // JSON should be readable as V1 (ignoreUnknown)
    var v1 = mapper.readValue(json, TransactionCreatedV1.class);
    assertThat(v1.accountId()).isEqualTo("hot-account-001");
    assertThat(v1.schemaVersion()).isEqualTo(2);
  }

  @Test
  void jsonFallbackForNonAvroBinary() {
    var hybrid = new HybridAvroJsonDeserializer();
    String json = "{\"eventId\":\"%s\",\"eventType\":\"TransactionCreated\",\"schemaVersion\":1,\"occurredAt\":\"2026-09-01T10:00:00Z\",\"transactionId\":\"%s\",\"accountId\":\"acc-1\",\"amount\":10,\"currency\":\"MXN\",\"type\":\"DEBIT\",\"metadata\":{}}"
        .formatted(UUID.randomUUID(), UUID.randomUUID());
    String out = hybrid.deserialize("topic", json.getBytes(StandardCharsets.UTF_8));
    assertThat(out).contains("acc-1");
  }
}
