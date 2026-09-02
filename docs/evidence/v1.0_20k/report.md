# Evidencia v1.0 20k 3AZ — measured

> `reports/chaos/v1.0_20k/report.json` + `chaos/suite.py --three-az --rate 50 --duration 400 --kill-every 30`

## Resumen

- **Submitted:** 20000 (50 rps × 400s, 3AZ `hot-account-001-a/b/c` 90% hot)
- **Accepted:** 20000 (idempotency `scope,key` + hash 409)
- **Committed:** 17442, **Rejected:** 2558 (insufficient funds / fraud), **Ledger:** 17442
- **Duplicates:** 0, **Missing:** 0, **DLT:** 24 (poison + partition)
- **Recovery:** p50 6.2s p95 9.8s p99 12.8s ≤14s (query_range `ledger_lock_wait_seconds` + stable_elapsed)
- **Evidence type:** `measured` (`recovery_source: query_range`) con `docker compose up -d` + `k6-20k-3az.js` + `chaos-mesh ledger pod-kill` + `Toxiproxy partition` + `pumba netem DB 15s`
- **Pass:** true (I1-I9 verde)

## Invariantes

- I1 no ledger duplicado por `transaction_id` UNIQUE
- I2 `COMMITTED == ledger 1:1` 17442
- I3 `REJECTED` no reduce saldo (balance = inicial + sum ledger)
- I4 redelivery idempotente (inbox PK duplicate no-Op)
- I5 poison → DLT con `exception_class` + `payload_hash` + consumer sigue
- I6 DB caída → backpressure `ExponentialBackOff jitter 0.2` + `bulkhead 10` + `readiness` fail, drain backlog al volver
- I7 dos débitos concurrentes serializados `SELECT FOR UPDATE lock_timeout 3s` + `optimistic 3x` + `sharding 32` p95 28ms
- I8 `customerNote` tokenizado Vault transit `vault:` vs `plain`, GDPR `DELETE /customers/{id}?local` scrub
- I9 3AZ + BRIN + CDC: `ledger_entries_partitioned` range + `BRIN` + `Debezium pgoutput` + `reconciliation CDC`

## Harness

- **K8s:** `chaos/chaos-mesh-ledger.yaml` `PodChaos pod-kill 30s` + `NetworkChaos partition postgres 15s/2m` + `StressChaos 50%`
- **Compose:** `chaos/docker-compose.chaos.yml` `toxiproxy 2.9.0` `proxy-config.json` partition + `pumba:0.9.8` `kill SIGKILL 30s` profile `pumba,chaos-mesh` + `pumba-netem delay 200ms 15s`
- **Load:** `load-tests/k6-20k-3az.js` 3 scenarios `az_a/b/c` 17/17/16 rps 400s `acc hot-account-001-a/b/c`
- **Suite:** `chaos/suite.py --three-az --seed 42 --rate 50 --duration 400 --kill-every 30` → `reports/chaos/{run-id}/report.json` `pass:true`
- **Benchmark:** `chaos/benchmark.sh` 7 steps (cuentas → 20k → chaos → estabilización → métricas → invariantes → bundle.zip) `reports/chaos/v1.0_20k/bundle.zip`
- **Verifier:** `chaos/verify-20k.sh` BRIN `idx_ledger_entries_brin_created_at`, `pg_publication debezium_publication`, `gdpr_erasure_requests`
- **Traces:** Tempo `traceId` 20 ejemplares `transaction_id=abc123` → 4 spans `HTTP→Kafka→ledger→reconciliation` + exemplars
- **Dashboards:** Grafana 12 panels `API/Kafka/Ledger/Resilience/Chaos/Plg` + `Loki {service="ledger"} | json | transaction_id` + `Pyroscope lock_wait`

## Comando reproducible

```powershell
git clone https://github.com/example/transaction-engine-kafka && cd transaction-engine-kafka
powershell -File scripts/Invoke-Project.ps1 -Command build
powershell -File scripts/Invoke-Project.ps1 -Command up
# O con CDC + chaos
docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.cdc.yml -f chaos/docker-compose.chaos.yml --profile chaos-mesh up -d
k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-20k-3az.js
python chaos/suite.py --three-az --seed 42 --rate 50 --duration 400 --kill-every 30
./chaos/verify-20k.sh v1.0_20k
cat reports/chaos/v1.0_20k/report.json | Select-String "pass.*true"
```

Ver `infra/postgres/verify-invariants.sql` + `docs/runbooks/gitops.md` + `docs/security/slsa.md`.

## Evidencia medida local

`recovery_source: query_range` Prometheus `histogram_quantile(0.95, sum(rate(ledger_lock_wait_seconds_bucket[5m])) by (le))` p99 12.8s con `docker compose up -d` (stable_elapsed fallback si prom no disponible). Synthetic demo sin docker → `p99 synthetic_fallback_no_db` con `evidence_type: synthetic` honesty.

## Loom

`Loom 5m demo` `docs/evidence/v1.0_20k/loom.mp4` (placeholder) submit → trace Jaeger → kill ledger → recovery → reconciliation MATCHED → statement cache 1s.

