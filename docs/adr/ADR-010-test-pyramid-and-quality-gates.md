# ADR-010: Pirámide de Tests y Quality Gates por Servicio

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-002 exactly-once, ADR-005 locking, ADR-009 capacidad, PLAN_ELEVACION_11_FASES_V2 Fase 1
- Superseeds: parcialmente `pom.xml:42` `coverage 0.60/0.80` sin branch

## Contexto

`v0.5.1` tenía `0.60 global / 0.80 ledger line` pero sin `branch 0.70`, `pitest 40%` solo en profile `pitest` nunca en CI, y contracts solo con `json-schema-validator` sin Pact provider/consumer. El hot-account `SELECT FOR UPDATE 3s` no tenía property-based 100 threads.

Para **v1.0** necesitamos gates que rompan PR si se baja locking o se rompe `BACKWARD`.

## Decisión

1. **JaCoCo por servicio:**
   - `ledger-service` `0.80 line / 0.70 branch` (`services/ledger-service/pom.xml:160`).
   - Resto `0.60 line / 0.50 branch` (`services/transaction-service/pom.xml:186` y `pom.xml:43` `coverage.global.branch 0.50`).
   - Gate en `verify` `jacoco:check` — bloquea `mvn verify`.

2. **Pitest:**
   - Nuevo profile `pitest-ledger` `mutation 60% / coverage 80%` (`pom.xml:173`) para `ledger` prod-hardened.
   - Perfil `pitest` legado `40%` sigue para `global`.
   - CI job `mutation-tests` ejecuta `mvn -pl services/ledger-service -Ppitest-ledger verify` (`ci.yml:90`).

3. **Contracts:**
   - `libs/event-contracts/src/test/java/com/example/transactionengine/contracts/PactTransactionCreatedConsumerTest.java:12` y `PactTransactionCreatedProviderTest.java:12` verifican `BACKWARD`: provider v2 JSON `customerNote` es leído por consumer V1 `ignoreUnknown true`.
   - `AvroWireCompatibilityTest.java:21` `SchemaCompatibility.checkReaderWriterCompatibility(v2,v1) == COMPATIBLE` para `.avsc`.
   - Fixture broker doc `docs/contracts/pact-broker.md` (sin broker externo, in-process).
   - CI job `contract-tests` (`ci.yml:90`) corre `Pact*Test,AvroWire*`.

4. **Property-based hot-account:**
   - `services/ledger-service/src/test/java/com/example/transactionengine/ledger/LedgerHotAccountPropertyTest.java:21` jqwik `1.9.3` (`pom.xml:44`) 100 threads con `AtomicInteger` duplicate counting.
   - Verifica `concurrentDebitsNeverCreateDuplicateLedger` con `@Property(tries=20)` y `hotAccount100ThreadsWithRealisticContention` 100 threads — no lost duplicates.
   - Lock timeout per-test 1500ms (`LedgerRepository.java:30` `@Value 3000` pero test podría override a `1500` para detectar deadlock rápido — futuro).

5. **Reconciliation & resilience:**
   - `ReconciliationKafkaContractTest.java:12` asegura classifier `MATCHED` con `outbox_pending 0` y `PENDING` si `outbox_pending 1`, repetibilidad 3 seeds.
   - `LedgerDbDownResilienceTest.java:12`, `FraudPoisonDltTest.java:12`, `NotificationIsolationTest.java:12` aseguran `Retryable vs Permanent` y DLT no revierte ledger.

## Alternativas

- **Solo JaCoCo line:** insuficiente para `if (schemaVersion !=1 && !=2)` — branch gate necesario.
- **Pact Broker real con docker:** descartado para F1 por coste; se documenta para Fase 5 Avro Wire.
- **jqwik vs JUnit Repeated:** jqwik da shrinking y generación de `BigDecimal` aleatoria, mejor para hot-account.

## Consecuencias

- PR que baje `ledger` line <80% o branch <70% falla en `verify`.
- PR que rompa `BACKWARD` (ej. renombrar `amount` sin default) falla en `AvroWireCompatibilityTest`.
- CI ahora tiene `contract-tests` y `mutation-tests` paralelos a `validate` — ~2min extra.
- `LoadInvariantsTest.java:65` ya hace 3× seed deterministic; Fase 1 lo complementa con Pact + property.

## Validación

- `mvn -B -ntp -Dtest=Pact*Test,AvroWireCompatibilityTest test -pl libs/event-contracts -am` verde.
- `mvn -B -ntp -pl services/ledger-service -am test -Dtest=LedgerHotAccountPropertyTest` verde (jqwik engine).
- `mvn -B -ntp verify -Ppitest-ledger -pl services/ledger-service -am -DskipITs` muestra `mutation 60%` (puede fallar si <60 — esperado hasta subir coverage).

## Referencias

- `pom.xml:42` coverage props, `services/ledger-service/pom.xml:160` jacoco, `.github/workflows/ci.yml:90` gates V2.
- `libs/event-contracts/src/main/avro/TransactionCreatedV1.avsc` + `V2.avsc:11`.
