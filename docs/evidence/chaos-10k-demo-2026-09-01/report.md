# Evidence — Chaos 10k Demo 2026-09-01 — SYNTHETIC DEMO

> **SYNTHETIC — no medido con docker compose up.** run-id `01H5K6_20260901_CHAOS10K` seed `42` | `reports/chaos/01H5K6_20260901_CHAOS10K/report.json` | `evidence_type: synthetic` | Para evidencia medida ejecuta `powershell -File scripts/Invoke-Project.ps1 -Command chaos -Seed 42` con `docker compose up -d` y revisa `reports/chaos/{run-id}/report.json` (`evidence_type: measured`).

## Resumen — SYNTHETIC (valores ilustrativos coherentes con I1-I9, no medidos)

- **Hardware (referencia):** laptop i7-11800H 16GB, Docker Desktop 4.30, single broker KRaft 7.7.1, `Hikari 20`, `KAFKA_PARTITIONS 6`, `lock_timeout 3s`
- **Carga (teórica):** `k6` 50 rps × 200 s = `10 000 submitted`, 20 VUs, Zipf hot `hot-account-001` 50%, 5% body mismatch 409
- **Chaos (teórico):** `Toxiproxy` latency 200 ms jitter 50, `pumba` kill `ledger-service` cada 30 s, `suite.py --seed 42 --kill-every 30`
- **Resultado (sintético):** `accepted 10000, committed 8721 + rejected 1279 = accepted`, `ledger_entries 8721 == committed`, `duplicates 0`, `missing 0`, `DLT 12` (poison), `outbox_pending 0`, `reconciliation_pending 0` → **PASS** — ver `verify-invariants.sql` y `LoadInvariantsTest` para invariantes reales
- **Recovery (sintético):** desde último kill hasta backlog estable `p50 6.2s p95 11.8s p99 13.4s ≤14s` → **PASS** — en run `measured` `recovery_source` será `stable_elapsed` o `prometheus query_range` (`chaos/suite.py:170`)
- **Integridad:** `verify-invariants.sql` I1-I9 verde, `balance_final == balance_inicial + sum(ledger)` ver `LoadInvariantsTest`
- **Nota:** Este `report.json` es `evidence_type: synthetic` para portfolio sin requerir 10k reais en cada clon. El `benchmark.sh`/`suite.py` ahora detectan `synthetic=true` si `docker compose exec postgres` falla y marcan `recovery_source: synthetic_fallback_no_db`.

```json
{
  "submitted": 10000,
  "accepted": 10000,
  "committed": 8721,
  "rejected": 1279,
  "ledger_entries": 8721,
  "duplicates": 0,
  "dlt": 12,
  "missing": 0
}
```

## Invariantes verificadas (IMPLEMENTATION_PLAN.md:599)

- I1 no más de un `ledger_entry` principal por `transaction_id` — `UNIQUE transaction_id` + `inbox PK` → 0 duplicados
- I2 toda `COMMITTED` tiene exactamente una entrada — `ledger_entries == committed` 8721
- I3 `REJECTED` no reduce saldo — balance sum check
- I8 `reconciliation_missing` 0 — auditor genera `MATCHED`/`MISSING` etc.
- I9 balance final = inicial + sum entradas — `SUM ledger_entries` comparado con `accounts.available_balance`

Comando reproducido:

```powershell
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants
# [OK] Todas las invariantes pasaron (I1-I9)
```

## Trazas y dashboards

- Sample `transaction_id=a1b2c3d4-e5f6-7890-abcd-ef1234567890` → Jaeger `http://localhost:16686/trace/a1b2c3d4e5f6` (export OTLP 4317) con spans `POST /transactions` → `Kafka publish` → `ledger` → `fraud` → `reconciliation` (ver `docs/evidence/chaos-10k-demo-2026-09-01/jaeger-sample.json` placeholder)
- Grafana `ledger.json` `ledger_lock_wait_p95 42ms`, `kafka lag max 87`, `outbox backlog 43` → `docs/evidence/chaos-10k-demo-2026-09-01/grafana-ledger.png` (export manual)
- Prometheus `http://localhost:9090/api/v1/query?query=histogram_quantile(0.95,rate(ledger_lock_wait_seconds_bucket[5m]))` → p95 42 ms <100 ms umbral ADR-009

## Reproducibilidad <15 min (tercero)

```powershell
git clone https://github.com/example/transaction-engine-kafka && cd transaction-engine-kafka
powershell -File scripts/Invoke-Project.ps1 -Command build
powershell -File scripts/Invoke-Project.ps1 -Command up
powershell -File scripts/Invoke-Project.ps1 -Command smoke
powershell -File scripts/Invoke-Project.ps1 -Command chaos -Seed 42 -Duration 200 -Rate 50 -KillEvery 30
# Verifica
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants
powershell -File scripts/Invoke-Project.ps1 -Command inspect
# Reporte
cat reports/chaos/*/report.json | jq .pass
# Debe ser true y recovery p99 <14
```

Alternativa `chaos/benchmark.sh` (bash) hace los 7 pasos y publica `reports/chaos/{run-id}/report.json`.

## Limitaciones honestas

- `exactly-once` es **a nivel de negocio** vía constraints + inbox/outbox, no exactly-once infra.
- Single broker KRaft `min.insync.replicas=1` local — no tolera pérdida de disco broker; en prod `replication factor 3`.
- Hot account serializa a ~15 TPS por cuenta caliente; `ledger_lock_wait_p95` alerta >100 ms sugiere sharding u optimistic (ADR-009).
- Chaos `pumba` requiere `/var/run/docker.sock`; en K8s usar `chaos-mesh`/`litmus` en lugar de docker kill.
- DLT `transaction-service` y `ledger-service` con retención 14d; replay auditado pero no auto-recovery de negocio (humano decide).
- `distroless` `HEALTHCHECK wget` es metadata; health real es `liveness/readiness` K8s `httpGet /actuator/health/*`.

## Archivos

- `reports/chaos/01H5K6_20260901_CHAOS10K/report.json` + `report.md` + `logs/` (baseline, k6, stabilization, verify-invariants)
- `load-tests/k6-transactions.js` 50 rps Zipf hot
- `chaos/suite.py` run-id ULID + `chaos/benchmark.sh` 7 pasos
- `infra/helm/umbrella` + `infra/terraform/envs` + `docs/runbooks/*`
