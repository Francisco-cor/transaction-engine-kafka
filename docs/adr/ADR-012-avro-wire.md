# ADR-012: Avro Wire y Gate Blocking

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-004 contratos, ADR-008 evolución (supereeded), docs/contracts/pact-broker.md, libs/event-contracts/src/main/avro/*.avsc
- Superseeds: ADR-008 runtime JSON → Avro wire opt-in

## Contexto

`ADR-008` declaró Avro como contrato build-time pero runtime JSON `StringSerializer` con `schema_version` header (F3). Para prod necesitamos **Avro wire** con `KafkaAvroSerializer` para validación en broker y `BACKWARD` blocking en CI, manteniendo fallback JSON para rolling update `producer v2 + consumer v1`.

## Decisión

1. **Wire opt-in via env:**
   - `services/transaction-service/src/main/resources/application.yml:36` `value-serializer: ${KAFKA_VALUE_SERIALIZER:StringSerializer}` + `fraud/ledger` `value-serializer/deserializer` env.
   - Default `StringSerializer` (dx <15 min). Para Avro: `KAFKA_VALUE_SERIALIZER=io.confluent.kafka.serializers.KafkaAvroSerializer` + `KAFKA_VALUE_DESERIALIZER=com.example.transactionengine.contracts.HybridAvroJsonDeserializer` + `SCHEMA_REGISTRY_URL`.
   - `docker-compose` doc: `KAFKA_VALUE_SERIALIZER Avro` en `.env.example` comentario.

2. **Producer builder:**
   - `services/transaction-service/src/main/java/com/example/transactionengine/transaction/application/TransactionApplicationService.java:109` cuando `isV2` construye `GenericRecordBuilder` con `TransactionCreatedV2.avsc` (F5-3) — `eventId/transactionId uuid`, `occurredAt timestamp-millis`, `amount decimal 19,4` via `DecimalConversion.toBytes`, `customerNote` tokenized. Valida `GenericDatumWriter` no throw; si Avro deshabilitado, sigue JSON `eventMap`.

3. **Consumer fallback:**
   - `libs/event-contracts/src/main/java/com/example/transactionengine/contracts/HybridAvroJsonDeserializer.java:11` `Deserializer<String>` que: magic byte `0` + 5 bytes header → `GenericDatumReader` con `TransactionCreatedV2.avsc` → `GenericRecord` → JSON string (con `Utf8`/`ByteBuffer`/`Long→Instant` conversions). Else → UTF-8 JSON string. Permite `ledger`/`fraud` `@KafkaListener String` seguir funcionando con Avro wire.
   - `ledger/fraud` `application.yml:29` `KAFKA_VALUE_DESERIALIZER` default `StringDeserializer`, opt-in `HybridAvroJsonDeserializer`.

4. **CI gate blocking:**
   - `.github/workflows/ci.yml:90` nuevo job `schema-compatibility` corre `mvn -pl libs/event-contracts -am generate-sources test -Dtest=AvroWireCompatibilityTest` (F5-2) — `BACKWARD` `v2 reader / v1 writer` `COMPATIBLE` o falla PR.
   - Luego `contract-tests` depende de `schema-compatibility` + `validate`.

5. **Tests:**
   - `AvroWireCompatibilityTest.java:21` BACKWARD v2/v1.
   - `AvroWireCoexistenceIT.java:11` Hybrid handles `JSON v1/v2` + `Avro binary v2 → JSON` + `BigDecimal` logical type.

## Alternativas

- **Solo JSON:** sin validación broker, riesgo break silencioso — descartado para prod.
- **Avro sin fallback:** requeriría coordinar `producer v2` y `consumer v2` downtime — descartado, F5 exige `hybrid` para rolling update.
- **SpecificRecord generated:** más performante pero acopla build; `GenericRecordBuilder` valida sin generar clase en `transaction-service` (menos churn).

## Consecuencias

- Rolling update: `transaction-service` Avro v2 puede desplegarse antes que `ledger` v1 JSON — `Hybrid` decodifica ambos.
- Si `KAFKA_VALUE_SERIALIZER=Avro` y `Hybrid` no configurado, consumers String fallan con `ClassCastException` — doc obliga a setear `Hybrid`.
- Decimales `19,4` via `DecimalConversion` — si `amount` no `setScale(4)` falla (ya normalizado `TransactionApplicationService.normalize`).
- CI bloquea PR que añade campo sin `default null` (incompatible BACKWARD).

## Validación

- `mvn -B -ntp -pl libs/event-contracts -am test -Dtest=AvroWireCompatibilityTest,AvroWireCoexistenceIT` verde.
- `KAFKA_VALUE_SERIALIZER=io.confluent.kafka.serializers.KafkaAvroSerializer KAFKA_VALUE_DESERIALIZER=com.example.transactionengine.contracts.HybridAvroJsonDeserializer docker compose up -d` → `POST /transactions` con `customerNote` → ledger `COMMITTED` con `customerNoteVault vault` (si Vault) o `plain`.

## Referencias

- `services/transaction-service/src/main/resources/application.yml:36`, `services/ledger-service/src/main/resources/application.yml:23`, `libs/event-contracts/src/main/java/com/example/transactionengine/contracts/HybridAvroJsonDeserializer.java:11`, `.github/workflows/ci.yml:90`.
