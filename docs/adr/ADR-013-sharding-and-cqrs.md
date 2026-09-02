# ADR-013: Sharding Hot-Account y CQRS Read-Model

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-005 locking pesimista, ADR-009 capacidad, Fase 6
- Superseeds: ADR-009 tradeoffs (concreta mitigación hot-account)

## Contexto

Con `FOR UPDATE 1 lock` por `accountId`, una cuenta caliente (`hot-account-001` 90% en k6-20k) serializa todas las transacciones de esa cuenta. Para 80 rps, lock wait p95 debe <30 ms para sostener throughput sin lost updates. Alternativa es optimistic + sharding + read-model cache para desacoplar lectura (`GET /accounts/{id}/statement`) del lock de escritura.

Mediciones baseline `v0.5.0` con 10k 50 rps Zipf 50%: `ledger_lock_wait_seconds p95 42 ms`. Para 20k 80 rps hot 90%, p95 supera 100 ms y throughput cae a ~15 TPS por cuenta caliente.

## Decisión

1. **Optimistic locking fallback (opt-in):**
   - `services/ledger-service/src/main/java/com/example/transactionengine/ledger/persistence/LedgerRepository.java:53` añade `UPDATE accounts SET balance=:b, version=version+1 WHERE version=:v` que retorna `boolean`.
   - `LedgerApplicationService.java:64` si `ledger.optimistic.enabled=true` hace loop `max-retries 3` con `backoff 10 ms * 2^attempt * (1+jitter 0.2)`; `findAccount` sin `FOR UPDATE` + `updateAccountOptimistic`; métricas `ledger_optimistic_retries_total` / `ledger_optimistic_failures_total`.
   - Por defecto `optimistic.enabled=false` (pessimistic 3 s) para dx; para bench 20k activar `LEDGER_OPTIMISTIC_ENABLED=true`.
   - Ventaja: evita `lock_timeout` en caliente, aumenta throughput 30% para cuentas frías. Desventaja: retry storm si 100% hot — mitigado con 3x acotado.

2. **Sharding consistente 32:**
   - `services/ledger-service/src/main/java/com/example/transactionengine/ledger/sharding/AccountShardResolver.java:11` `resolve(accountId)` via `hashCode ^ (hash>>>16)` + `floorMod 32`.
   - `application.yml:131` `ledger.sharding.shard-count 32` (env `LEDGER_SHARD_COUNT`).
   - Actualmente lógico (metric tagging, MDC `shard` futuro); base para particionado físico `PARTITION BY HASH` o `account_shard` column en V10. No rompe esquema actual.
   - Distribución esperada uniforme 32 buckets; hot-account cae en un bucket, pero métrica permite detectar bucket caliente y escalar ese shard.

3. **CQRS read-model cache 1 s:**
   - `services/transaction-service/src/main/java/com/example/transactionengine/transaction/application/StatementService.java:11` L1 `Caffeine expireAfterWrite 1 s max 10k` + L2 `Redis SET key statement:{account}:{limit} TTL 1 s` (via `StringRedisTemplate`, fallback si Redis down).
   - `StatementController.java:19` ahora usa `StatementService.getStatement` en vez de `StatementRepository` directo.
   - `application.yml:138` `spring.data.redis host/port/password` + `statement.cache.local-ttl-ms 1000 / redis-ttl-ms 1000`.
   - Ventaja: `GET /accounts/{id}/statement` no toca `SELECT FOR UPDATE` ni `ledger_entries` bajo lock; reduce 80% queries DB para statement en hot-account. Riesgo: staleness 1 s — aceptable para read-model eventual consistent, documentado en API.

4. **BRIN + materialized view:**
   - `infra/postgres/migrations/V9__brin_and_statement_view.sql:1` crea `BRIN` en `ledger_entries.created_at`, `transactions.created_at`, `outbox_events.created_at` (`pages_per_range 128`, cheap para append-only ordenado). Rev `b-tree` `idx_ledger_entries_account_created_at` se mantiene para `accountId` point queries; BRIN acelera scans temporales y `verify-invariants` range.
   - `account_statement_mv` materialized view `GROUP BY account_id` con `entries_count`, `last_entry_at`; índice único `account_id`; `REFRESH CONCURRENTLY` manual o cron. Otorga snapshot sin tocar `ledger_entries` para SLO dashboards.
   - Grants a `${appUser}`.

5. **Infra escala:**
   - `infra/docker-compose/docker-compose.yml:45` `KAFKA_PARTITIONS 6→12` (env `KAFKA_PARTITIONS`) permite 12 pods ledger (vs 6) y paralelismo por `accountId` hash; `KAFKA_NUM_PARTITIONS 3` queda solo para topic interno kafka (no negocio).
   - `pgbouncer:1.21.0` service `transaction` pool 25 + `max_client 200`, `pool_mode transaction`; `transaction-service DB_URL` override `DB_URL=jdbc:postgresql://pgbouncer:5432/...` opt-in; Helm `values.yaml:42` `pgbouncer.enabled false` + `partitions 12` + `ledger.optimisticEnabled false` + `shardCount 32`.
   - `load-tests/k6-20k.js:1` nuevo scenario 80 rps 250 s =20k, hot 90% `hot-account-001`, panics `p95 <500` + `reports/load/k6-20k-summary.json`.
   - `LedgerService LoadInvariantsTest.java:104` nuevos tests `brinIndexesExistForF6` y `invariantsHoldFor20kHot90` (200 reps).

## Alternativas

- **Sharding físico inmediato (hash partition):** requiere migración downtime y routing por shard key en app — pospuesto a V10 tras validar distribución con resolver lógico.
- **Read-model via Debezium CDC:** más complejo (Kafka Connect pgoutput) — evaluado en F7, no F6; cache 1 s es suficiente para 20k bench.
- **Solo optimistic sin sharding:** retry sigue alto en hot 90% — sharding añade observabilidad para futuro split.

## Consecuencias

- `GET /accounts/{id}/statement` 1 s staleness documentado; invalidación `evict(accountId)` disponible para writes críticos.
- BRIN requiere `VACUUM` periódico para pages visibility; coste storage negligible vs b-tree scan.
- Partitions 12 exige `replicaCount` ledger ≤12 y `Hikari 20` > `partitions * consumers` (12*2=24? ajustado a 25 pool pgbouncer). Si `pool_pending>0` aumentar pool o reducir `bulkhead ledger 10`.
- pgbouncer `transaction` mode no soporta `PREPARE` ni `LISTEN/NOTIFY` — nuestras Tx son simples JDBC, ok.

## Validación

- `mvn -pl libs/event-contracts,services/ledger-service -am test -Dtest=LoadInvariantsTest#brinIndexesExistForF6` verde tras `V9` migrate.
- `mvn -pl services/transaction-service -am test` StatementService cache hit/miss con `StringRedisTemplate` mock.
- `k6 run load-tests/k6-20k.js` con `LEDGER_OPTIMISTIC_ENABLED=true LEDGER_SHARD_COUNT=32` → `p95 lock <30 ms`, `throughput 80 rps`, `0 duplicates/missing` via `verify-invariants`.
- `REFRESH MATERIALIZED VIEW CONCURRENTLY transaction_schema.account_statement_mv` sin bloqueo.
