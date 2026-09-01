# Capacidad y Tuning

## Pool DB

- Hikari `maximum-pool-size` 20, `minimum-idle` 5, `connection-timeout` 3s, `leak-detection` 2s
- Para 50 rps y 3 consumers con `FOR UPDATE`, pool 20 evita starvation. Monitorear `hikaricp_connections_pending` y `ledger_lock_wait_seconds`.
- Lock wait p95 <100ms objetivo; si >200ms considerar `ledger.lock.timeout-ms` 3000 -> 1500 o sharding por cuenta.

## Kafka

- Particiones 6 por topic (env `KAFKA_PARTITIONS`, default 6) para permitir paralelismo por `accountId` key.
- Compression `zstd` en productores, `acks=all`, `enable.idempotence=true`.
- Retention negocio 7d, DLT 14d.
- Throughput estimado local: single broker ~5k msg/s, suficiente para 50 rps demo.

## Producers

- `batch-size` outbox 50, `lease` 30s, `poll-delay` 1s. Para 10k benchmark, lag <5s.

## Verificación

```powershell
docker compose -f infra/docker-compose/docker-compose.yml exec kafka kafka-topics --bootstrap-server kafka:29092 --describe
k6 run load-tests/k6-transactions.js
powershell -File scripts/Invoke-Project.ps1 -Command inspect
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants
```
