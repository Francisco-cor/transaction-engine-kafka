# ADR-006: Estrategia DLT y replay

- Estado: aceptado
- Fecha: 2026-09-01
- Sustituye: n/a
- Relacionado: ADR-002 exactly-once, ADR-005 locking

## Contexto

Kafka entrega at-least-once y el consumidor puede fallar de forma permanente (poison) o transitoria (DB caída).
Sin una estrategia de Dead Letter Topic (DLT) clara, un poison bloquea la partición y un fallo
transitorio se reintenta infinitamente. Se requiere aislamiento, diagnóstico y replay auditado.

## Decisión

1. **Clasificación de excepciones:** `Permanent*Exception` → DLT inmediato, `Retryable*Exception` → retry finito.
2. **Retry:** `DefaultErrorHandler` con `FixedBackOff(retry-interval, maxAttempts-1)` inicial, evolucionará a
   `ExponentialBackOff + jitter` en Fase 4. `isolation.level=read_committed` en consumers.
3. **DLT:** destino `topic.group.DLT` (p.ej. `transactions.created.v1.ledger-service.DLT`), con headers enriquecidos:
   `exception_class`, `exception_message`, `failure_count`, `first_failure_at`, `last_failure_at`,
   `payload_hash`, `consumer_group`. `commitRecovered=true`, `ackAfterHandle=true` para no bloquear offsets.
4. **Replay:** endpoint administrativo `POST /admin/dlt/{id}/replay` y `POST /reconciliation/{id}/replay`
   con `X-Replay-Reason` obligatorio, `dry_run` opcional, auditoría en `dlt_replay_audit` /
   `replay_audit`, idempotente (replay no duplica ledger vía inbox dedup + constraint `ledger_entries.transaction_id UNIQUE`).
5. **Retención:** DLT `1209600000 ms` (14 días) vs negocio `604800000` (7 días) en `docker-compose.yml:44`.

## Alternativas descartadas

- **Retry topic intermedio con backoff en Kafka:** más complejo operativamenete, requiere scheduler de reinyectar;
  se evaluará si `maxAttempts=3` no es suficiente para picos de DB.
- **Descartar silenciosamente poison:** perdería trazabilidad para auditoría.

## Consecuencias

- DLT no es cementerio: requiere alertas `DLT rate >0`, dashboard, runbook y ownership `ledger-service`.
- Replay es operación privilegiada: necesita scope `admin:replay` (Fase 3) y redacción de PII en logs.
- Cada consumer define su propia DLT; reconciliación detecta `MISSING`/`DUPLICATE` y guía replay.
- Invariantes `I1-I9` en `infra/postgres/verify-invariants.sql` cubren que replay no duplica efecto financiero.

## Validación

- `LedgerListenerTest`, `LedgerServicePostgresIntegrationTest.duplicateAndCrashAfterCommitDoNotDuplicateLedger`
- `LedgerKafkaConfiguration` con `DeadLetterPublishingRecoverer` + `DefaultErrorHandler`
- `verify-invariants` debe pasar tras replay.
