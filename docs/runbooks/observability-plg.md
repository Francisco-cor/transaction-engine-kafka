# Runbook — PLG Observability y SLOs con Exemplars

> Stack PLG: `prometheus:9090` + `loki:3100` + `tempo:3200` + `otel-collector:4317` + `pyroscope:4040` + `grafana:3000` + `alertmanager:9093`
> Profundidad: F2 `tail_sampling` 10% + `exemplars` + `W3C baggage` + `recording rules` + `pyroscope`

## 1. Búsqueda por `transaction_id` (PLG)

El `transaction_id` es la clave de correlación en todos los componentes.

**LogQL (Loki):**

```logql
{service="transaction-service"} | json | transaction_id="a1b2c3d4-e5f6-7890-abcd-ef1234567890"
{service="ledger-service"} | json | transaction_id=~".+" | trace_id=~".+"
```

**Tempo (TraceQL):**

```traceql
{resource.service.name="ledger-service" && transaction_id="a1b2c3d4-e5f6-7890-abcd-ef1234567890"}
```

**Prometheus + exemplars:**

En Grafana Explore `Prometheus` activar `Exemplars` → click en punto `ledger_lock_wait_seconds_bucket` → salto a `Tempo` trace con `trace_id`.

**SQL fallback sin PLG:**

```sql
SELECT * FROM transaction_schema.transactions WHERE transaction_id='abc123';
SELECT * FROM transaction_schema.inbox_events WHERE transaction_id='abc123';
SELECT * FROM transaction_schema.ledger_entries WHERE transaction_id='abc123';
```

## 2. Dashboards PLG (12+ panels)

| Dashboard | UID | Panels clave | Fuente |
|---|---|---|---|
| API | `api-transaction` | throughput, p95/p99 con exemplars, 409/429, Loki logs, Tempo traces | prometheus + loki + tempo |
| Kafka | `kafka-lag` | lag sum, DLT rate, oldest age, outbox backlog max | prometheus |
| Ledger | `ledger-correctness` | committed/rejected, duplicates, DB p95, lock wait p95/p99 exemplars, outbox pending, Pyroscope flame | prometheus + pyroscope |
| Resilience | `resilience-cb` | CB state, retries, bulkhead, Hikari pending | prometheus |
| Chaos | `chaos-10k` | submitted/committed, missing/duplicates, recovery p95 (recording rule), Loki kill logs, Tempo sampled trace | prometheus + loki + tempo |

Provisioning: `infra/observability/grafana/provisioning/dashboards` + `datasources` con `exemplarTraceIdDestinations: tempo` y `tracesToLogsV2: loki`.

## 3. Tail sampling y exemplars

`infra/observability/collector-config.yml:14` `tail_sampling` con 4 políticas:

- `errors` → `status_code ERROR` 100%
- `dlt` → `event_type TransactionRejected` 100%
- `slow` → `latency >500ms` 100%
- `probabilistic 10%` → resto

Exemplars: `service.pipelines.metrics.exporters: prometheus` con `exemplars.enabled true` + `prometheus.yml:44` `enable_feature: exemplar-storage` + `enable_exemplar: true` por job `otel-collector`.

Verifica:

```bash
curl http://localhost:8889/metrics | grep exemplar
curl http://localhost:9090/api/v1/query?query=job:ledger_lock_wait:p95
```

## 4. Pyroscope profiling

`ledger-service` env `PYROSCOPE_SERVER_ADDRESS=http://pyroscope:4040` (compose). Para profiling continuo de `lock_wait`:

```bash
# En ledger trace, span lock_wait → Pyroscope tag transaction_id
curl http://localhost:4040/pyroscope/render?query=process_cpu
```

Grafana panel `Pyroscope lock_wait CPU` muestra flame de `LedgerRepository.lockAccount`.

## 5. W3C baggage

`TraceContext.baggage(transaction_id, account_id)` → header `baggage: transaction_id=abc,account_id=xyz` en `transactions.created.v1`. `LedgerListener` lee `baggage` y taguea span. Permite filtrar Tempo por `baggage`.

## 6. SLOs con recording rules

`infra/observability/recording-rules.yml:5`:

- `job:http_requests:rate5m`
- `job:ledger_lock_wait:p95` (para alert `HighApiLatency`)
- `job:ledger_lock_wait:p99`
- `job:kafka_lag:sum`
- `job:outbox_backlog:max`

Alertas `prometheus-rules.yml:15` 12 reglas incluyendo `OtelCollectorDown`, `LokiIngestionErrors`, `TempoNoTraces`, `PyroscopeProfilingDown`.

## 7. Troubleshooting PLG

**Síntoma: Grafana sin exemplars**

1. `curl http://otel-collector:8889/metrics | grep ledger_lock_wait`
2. Ver `collector-config.yml` `spanmetrics.exemplars.enabled true`
3. Ver `prometheus.yml` `enable_exemplar: true`

**Síntoma: Loki sin logs**

1. `curl http://loki:3100/ready`
2. Ver `logback-spring.xml` `includeMdc true` + `mdcKeyFieldName trace_id`
3. `docker compose logs loki | grep error`

**Síntoma: Tempo sin traces**

1. `curl http://tempo:3200/status`
2. Ver `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317` en 6 servicios
3. `docker compose logs otel-collector | grep otlp`

## 8. Verificación F2

```powershell
docker compose -f infra/docker-compose/docker-compose.yml config --quiet
docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.exporters.yml -f infra/docker-compose/docker-compose.plg.yml config --quiet # futuro
curl http://localhost:9090/-/ready
curl http://localhost:3100/ready
curl http://localhost:3200/status
curl http://localhost:9093/-/healthy
# Generar transacción y buscar exemplar
$body='{"accountId":"demo-acc-001","amount":10.00,"type":"DEBIT","currency":"MXN"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/transactions -Headers @{'Idempotency-Key'='plg-test';'X-Tenant-Id'='demo'} -Body $body -ContentType 'application/json'
# Luego en Grafana Explore: Prometheus → job:ledger_lock_wait:p95 → click exemplar → Tempo
```
