# Pact Broker — TransactionCreated v1/v2

> F1 contract-tests job en `.github/workflows/ci.yml:90` ejecuta `PactTransactionCreated*` + `AvroWireCompatibilityTest` sin broker externo (consumer-driven in-process).

## Estrategia

- **Consumer:** `ledger-service` y `fraud-service` deserializan `TransactionCreatedV1` con `@JsonIgnoreProperties(ignoreUnknown=true)` (`libs/event-contracts/src/main/java/com/example/transactionengine/contracts/TransactionCreatedV1.java:9`) → toleran `customerNote` v2.
- **Provider:** `transaction-service` `TransactionApplicationService.java:90` emite `schemaVersion=2` solo si `customerNote != null`, sino `1`. Payload es `LinkedHashMap` → `ObjectMapper` JSON con `additionalProperties` controlado.
- **Avro fuente de verdad:** `libs/event-contracts/src/main/avro/TransactionCreatedV1.avsc` y `TransactionCreatedV2.avsc:11` `["null","string"] default null` → `BACKWARD` verificada por `AvroWireCompatibilityTest.java:21` `SchemaCompatibility.checkReaderWriterCompatibility(v2, v1) == COMPATIBLE`.

## Ejecutar local

```powershell
mvn -B -ntp -Dtest=Pact*Test,AvroWireCompatibilityTest -DfailIfNoTests=false test -pl libs/event-contracts -am
mvn -B -ntp -pl libs/event-contracts -am test # incluye AvroWire + Pact
```

CI:

```yaml
# .github/workflows/ci.yml contract-tests
mvn -B -ntp -Dtest=Pact*Test,AvroWire*,Schema*Compatibility* -DfailIfNoTests=false test
```

## Matriz verificada

| Provider | Consumer | Resultado |
|---|---|---|
| v1 JSON sin `customerNote` | V1 consumer | PASS `PactTransactionCreatedConsumerTest:37` |
| v2 JSON con `customerNote` | V1 consumer | PASS `PactTransactionCreatedProviderTest:27` ignoreUnknown |
| Avro V2 reader + V1 writer | — | PASS `AvroWireCompatibilityTest` BACKWARD |
| Avro V1 fields ⊆ V2 fields | — | PASS |

## Futuro: Pact Broker real

Para broker externo (`pactfoundation/pact-broker`):

```yaml
# docker-compose.pact.yml
pact-broker:
  image: pactfoundation/pact-broker:2.112.0
  ports: ["9292:9292"]
  environment: {PACT_BROKER_DATABASE_URL: postgres://...}
```

Y publicar con `mvn pact:publish` + `pact:verify` con `confluent:compatibility-check` blocking (Fase 5 Avro Wire lo conecta a Schema Registry).

## Nota

Provider v2 JSON con `customerNote` **no** valida contra `docs/contracts/transaction-created.v1.json` (`additionalProperties false`) — esperado. El contrato es deserialize-tolerant, no schema-strict. V1 schema se mantiene para fixtures canónicas; V2 fixture en `docs/contracts/transaction-created.v2.json` (próximo).

