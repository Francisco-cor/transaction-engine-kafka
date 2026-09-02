package com.example.transactionengine.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.kafka.common.serialization.Deserializer;

/**
 * F5 hybrid deserializer: tries Avro binary (Confluent wire format) then JSON String.
 * Returns JSON String so existing @KafkaListener String handlers keep working (fallback).
 * If Avro binary, converts GenericRecord to JSON string via TransactionCreatedV1 mapping.
 */
public class HybridAvroJsonDeserializer implements Deserializer<String> {

  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Override
  public String deserialize(String topic, byte[] data) {
    if (data == null) return null;
    // Confluent Avro wire format: magic byte 0 + 4 bytes schema id + Avro binary
    if (data.length > 5 && data[0] == 0) {
      try {
        var schemaFile = new java.io.File("libs/event-contracts/src/main/avro/TransactionCreatedV2.avsc");
        if (!schemaFile.exists()) schemaFile = new java.io.File("src/main/avro/TransactionCreatedV2.avsc");
        Schema schema = null;
        if (schemaFile.exists()) {
          schema = new Schema.Parser().parse(schemaFile);
        } else {
          try (var is = getClass().getResourceAsStream("/avro/TransactionCreatedV2.avsc")) {
            if (is != null) schema = new Schema.Parser().parse(is);
          }
        }
        if (schema != null) {
          var reader = new GenericDatumReader<GenericRecord>(schema);
          var decoder = DecoderFactory.get().binaryDecoder(data, 5, data.length - 5, null);
          GenericRecord rec = reader.read(null, decoder);
          var map = new java.util.LinkedHashMap<String, Object>();
          for (var f : schema.getFields()) {
            Object v = rec.get(f.name());
            if (v instanceof org.apache.avro.util.Utf8) v = v.toString();
            if (v instanceof java.nio.ByteBuffer) {
              var logical = f.schema().getLogicalType();
              if (logical instanceof org.apache.avro.LogicalTypes.Decimal) {
                var conv = new org.apache.avro.Conversions.DecimalConversion();
                v = conv.fromBytes((java.nio.ByteBuffer) v, f.schema(), logical);
              }
            }
            if (v instanceof Long && "occurredAt".equals(f.name())) {
              // timestamp-millis -> ISO string
              v = java.time.Instant.ofEpochMilli((Long) v).toString();
            }
            map.put(f.name(), v);
          }
          return mapper.writeValueAsString(map);
        }
      } catch (Exception e) {
        // fallback to JSON
      }
    }
    // JSON fallback (default outbox) — return as String
    return new String(data, java.nio.charset.StandardCharsets.UTF_8);
  }

  @Override
  public void configure(Map<String, ?> configs, boolean isKey) {}

  @Override
  public void close() {}
}
