# ADR-008: Evolución de esquemas y compatibilidad

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-004 contratos, ADR-006 DLT, ADR-007 trazas

## Contexto

Los eventos `TransactionCreated`, `TransactionCommitted`, `FraudDecision` se publican en Kafka y son
consumidos por `ledger`, `fraud`, `reconciliation` y futuros `notification`. Cambiar la forma de un evento
sin regla rompe consumidores y despliegues sin downtime. Se requiere una política de compatibilidad
que permita `producer v2 + consumer v1` durante rolling update.

## Decisión

1. **Formato:** Avro con Schema Registry (Confluent 7.7.1) para validación estática; JSON sigue siendo
   el `content_type` por defecto en local por simplicidad, pero la fuente de verdad son los `.avsc`
   en `libs/event-contracts/src/main/avro/`.
2. **Compatibilidad:** `BACKWARD` para todos los subjects (`transactions.created`, `transactions.committed`,
   `fraud-decisions`). Campos nuevos **obligatoriamente** opcionales con `default` o `union ["null", type]`.
3. **Reglas:**
   - No renombrar ni cambiar semántica de un campo existente dentro de la misma versión.
   - Campos nuevos deben tener `default` (null, 0, "", {}) y ser ignorados por consumidores viejos
     (`@JsonIgnoreProperties(ignoreUnknown=true)` en `TransactionCreatedV1.java:9`).
   - Cambios incompatibles crean nuevo `subject` o `topic` (`v2`) y migración explícita con dual-read.
   - `schema_version` se propaga en header Kafka `schema_version` y payload `schemaVersion`.
4. **Pipeline:** 
   - `mvn generate-sources` genera clases Avro desde `src/main/avro/*.avsc`.
   - CI ejecuta `SchemaCompatibility.checkReaderWriterCompatibility` y `confluent schema-registry:compatibility-check`
     (bloquea PR si incompatible).
   - Fixtures canónicos en `docs/contracts/*.json` y `openapi-transaction-service.yaml` se validan en cada `verify`.
5. **Ejemplo v2:** `TransactionCreatedV2.avsc` añade `customerNote: ["null","string"] default null`.
   Producer `transaction-service` emite `schema_version=2` cuando `customerNote != null`, sino `1`.
   Consumers `ledger`/`fraud` aceptan `1|2` en `validateEvent`.
6. **Deprecación:** Un campo marcado deprecated se mantiene 2 versiones, luego se elimina solo tras
   confirmar que no hay consumers con `schema_version < N`. Proceso en `docs/contracts/README.md`.

## Alternativas

- **Solo JSON sin registry:** sin validación automática, riesgo de break silencioso. Descartado.
- **FORWARD only:** permitiría que viejos producers rompan nuevos consumers; no sirve para rolling update donde
  producer nuevo precede a consumer nuevo. Por eso `BACKWARD`.

## Consecuencias

- Despliegue sin downtime: se puede liberar `transaction-service v2` antes que `ledger v1` sin coordinación.
- Matriz de coexistencia probada en `SchemaCoexistenceTest.java:1` y `ContractValidationTest.java`.
- Añadir campo es barato; eliminar/renombrar es caro y requiere ADR nuevo.
- Cada cambio de `.avsc` debe ir con actualización de `docs/contracts/*.json` y `openapi` si afecta API.

## Validación

- `AvroCompatibilityTest` + `SchemaCoexistenceTest` verde en `mvn verify`.
- Smoke `POST /transactions` con `customerNote` acepta 202 y ledger/fraud lo procesan como `COMMITTED` sin ver nota.
- `docker compose exec schema-registry curl /subjects/transactions.created.v1/versions/latest` muestra `BACKWARD`.
