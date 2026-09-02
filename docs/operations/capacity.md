# Capacidad y Tuning

## Pool DB

- Hikari `maximum-pool-size` 20, `minimum-idle` 5, `connection-timeout` 3s, `leak-detection` 2s
- Para 50 rps y 3 consumers con `FOR UPDATE`, pool 20 evita starvation. Monitorear `hikaricp_connections_pending` y `ledger_lock_wait_seconds`.
- Lock wait p95 <100ms objetivo; si >200ms considerar `ledger.lock.timeout-ms` 3000 -> 1500 o sharding por cuenta.
- **F6:** `pgbouncer` `pool 25` `max_client 200` `transaction` mode reduce conexiones directas PG; apps pueden usar `DB_URL=jdbc:postgresql://pgbouncer:5432/transactions` (`infra/docker-compose/docker-compose.yml:66`).
- **F6:** BRIN `idx_ledger_entries_brin_created_at` + `account_statement_mv` reducen scans temporales; `REFRESH MATERIALIZED VIEW CONCURRENTLY` para read-model.

## Kafka

- Particiones 12 por topic (env `KAFKA_PARTITIONS`, default 12) para permitir paralelismo por `accountId` key (F6 bump 6→12).
- Compression `zstd` en productores, `acks=all`, `enable.idempotence=true`.
- Retention negocio 7d, DLT 14d.
- Throughput estimado local: single broker ~5k msg/s, suficiente para 50 rps demo, 20k bench 80 rps con 12 partitions.

## Ledger Locking y Sharding

- **Pessimistic** `SELECT FOR UPDATE lock_timeout 3s` por defecto; métrica `ledger_lock_wait_seconds` p95 target <42 ms (v0.5) → <30 ms con F6 optimistic+sharding.
- **Optimistic** opt-in `LEDGER_OPTIMISTIC_ENABLED=true` `UPDATE ... WHERE version=:v` retry 3x backoff 10ms*2^attempt jitter 0.2; métricas `ledger_optimistic_retries_total` / `failures`.
- **Sharding** `AccountShardResolver` 32 shards consistent hash `ledger.sharding.shard-count`; futuro particionado físico `PARTITION BY HASH`; hot-account-001 cae en un shard detectable.
- **Read-model** `StatementService` L1 Caffeine 1s + L2 Redis 1s (`statement.cache.*`); `GET /accounts/{id}/statement` eventual consistent 1s staleness.

## Producers

- `batch-size` outbox 50, `lease` 30s, `poll-delay` 1s. Para 10k benchmark, lag <5s; para 20k 80 rps, lag <8s con pgbouncer.

## Verificación

```powershell
docker compose -f infra/docker-compose/docker-compose.yml exec kafka kafka-topics --bootstrap-server kafka:29092 --describe
k6 run load-tests/k6-transactions.js
k6 run load-tests/k6-20k.js  # F6 20k hot 90% 80rps
powershell -File scripts/Invoke-Project.ps1 -Command inspect
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants
# F6 bench con optimistic:
# $env:LEDGER_OPTIMISTIC_ENABLED="true"; docker compose up -d; k6 run load-tests/k6-20k.js
psql -c "SELECT * FROM pg_indexes WHERE indexname like '%brin%'"
psql -c "REFRESH MATERIALIZED VIEW CONCURRENTLY transaction_schema.account_statement_mv"
```
