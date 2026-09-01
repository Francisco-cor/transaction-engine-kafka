# Contracts

Fuente de verdad: `libs/event-contracts/src/main/avro/*.avsc` (Avro) y `docs/contracts/*.json` (JSON Schema draft-07).

## Schemas

| Subject | File | Version | Compat |
|---|---|---|---|
| `transactions.created` | `TransactionCreatedV1.avsc` / `V2.avsc` | v1 base, v2 +`customerNote` | BACKWARD |
| `transactions.committed` | `transaction-committed.v1.json` | v1 | BACKWARD |
| `transactions.fraud-decisions` | `FraudDecisionV1.avsc` | v1 | BACKWARD |
| `transactions.committed` | `transaction-rejected.v1.json` | v1 | BACKWARD |

## Matriz de compatibilidad

| Producer → Consumer | Resultado | Test |
|---|---|---|
| v1 → v1 | ✅ | `ContractValidationTest` |
| v2 (customerNote) → v1 | ✅ ignorado | `SchemaCoexistenceTest.v1ConsumerCanReadV2PayloadWithCustomerNote` |
| v1 → v2 | ✅ default null | `SchemaCoexistenceTest.v1PayloadReadByV2WithDefaultNull` |
| Avro v2 → Avro v1 | ✅ BACKWARD | `SchemaCompatibility.checkReaderWriterCompatibility(v1,v2)` |
| v2 sin default → v1 | ❌ bloqueado por CI | `avro-maven-plugin` + registry check |

## Reglas

- Campos nuevos: `["null", type]` default null + `@JsonIgnoreProperties`.
- No renombrar, no cambiar tipo, no eliminar sin nuevo subject.
- `openapi-transaction-service.yaml` refleja API HTTP, no eventos; mantener sincronizado.

## Comandos

```bash
mvn -pl libs/event-contracts generate-sources
mvn -pl services/transaction-service -Dtest=SchemaCoexistenceTest test
curl http://localhost:8081/subjects/transactions.created.v1/versions/latest | jq .
curl -X PUT http://localhost:8081/config/transactions.created.v1 -H Content-Type:application/json -d '{"compatibility":"BACKWARD"}'
```
