# Runbook — Observabilidad y SLOs

> Local stack: `prometheus:9090`, `grafana:3000` (admin/admin_dev), `jaeger:16686`, `prometheus-rules.yml`

## 1. Búsqueda por `transaction_id`

El `transaction_id` es la clave de correlación obligatoria en todos los componentes
(`IMPLEMENTATION_PLAN.md:291`). Debe aparecer en:

- HTTP span `POST /transactions` y `GET /transactions/{id}`
- Kafka headers `traceparent`, `correlation_id`, `event_id` y `account_id`
- Logs JSON `transaction_id`, `event_id`, `account_id`, `trace_id`, `span_id`
- Métricas `ledger_committed_total` con tag `transaction_id` (cuando aplique)
- DLT headers y `reconciliation_results.details`

**Queries:**

```promql
# Logs (Loki/ELK)
{service="transaction-service"} | json | transaction_id="abc123"

# Jaeger
trace_id=X -> search tags transaction_id=abc123

# Prometheus - throughput por transaction (si etiquetado)
increase(http_server_requests_seconds_count{uri="/transactions"}[5m])
```

En local sin Loki, verificar vía `psql`:

```sql
SELECT transaction_id, status, reason_code FROM transaction_schema.transactions WHERE transaction_id='abc123';
SELECT * FROM transaction_schema.inbox_events WHERE transaction_id='abc123';
SELECT * FROM transaction_schema.ledger_entries WHERE transaction_id='abc123';
SELECT * FROM transaction_schema.reconciliation_results WHERE transaction_id='abc123';
```

## 2. Dashboards

| Dashboard | UID | Panel clave | Alerta |
|---|---|---|---|
| API | `api-transaction` | throughput, p95, 409, 429 | p95>1s |
| Kafka | `kafka-lag` | lag, DLT rate, oldest age, outbox backlog | lag>1000, DLT>0 |
| Ledger | `ledger-correctness` | committed/rejected, duplicates, DB latency | duplicates>0 |
| Resilience | `resilience-cb` | CB state, retries, pool | CB open, pool pending>5 |
| Chaos | `chaos-10k` | submitted/accepted/committed, recovery | missing>0 |

Grafana provisioning: `infra/observability/grafana/provisioning/dashboards/dashboards.yml` → `/var/lib/grafana/dashboards/*.json`.

## 3. Métricas y sampling

- `management.tracing.sampling.probability=1.0` local, `0.1` en demo con `always` para errores/DLT.
- `management.metrics.tags.application/environment` en cada `application.yml`.
- Logs JSON via `libs/observability/logstash-logback-encoder` con MDC `trace_id/span_id`.

## 4. Troubleshooting

### Síntoma: Grafana sin datos
1. `curl http://localhost:9090/-/ready` y `curl http://transaction-service:8080/actuator/prometheus`
2. Ver `infra/observability/prometheus.yml` targets deben ser DNS compose (`transaction-service:8080` no host.docker.internal).
3. `docker compose logs prometheus | grep target`

### Síntoma: Traces incompletos
1. Ver `traceparent` en `outbox_events.headers` JSON
2. Verificar `MdcFilter` y `LedgerListener` MDC/tag
3. `docker compose logs jaeger | grep otlp`

### Síntoma: Logs sin `transaction_id`
1. Verificar `TraceContext.putMdc` en listeners y `MdcFilter` en HTTP
2. Ver `logback-spring.xml` `<includeMdc>true</includeMdc>` y `mdcKeyFieldName`

## 5. SLOs (docs/operations/slo.md)

| Señal | Objetivo | Alerta |
|---|---|---|
| API p95 | ≤500ms | `histogram_quantile(0.95, ...) >1` |
| Recovery | ≤14s | `chaos_recovery_seconds >14` |
| Outbox backlog | 0 al estabilizar | `outbox_pending_events >0` for 60s |
| DLT rate | 0 | `rate(kafka_DLT[5m])>0` |
| DB pool pending | 0 | `hikaricp_connections_pending>0` |

Alertas definidas en `infra/observability/prometheus-rules.yml`.

## 6. Verificación tras Fase 2

```powershell
powershell -File scripts/Invoke-Project.ps1 -Command smoke
powershell -File scripts/Invoke-Project.ps1 -Command inspect
curl http://localhost:8080/v3/api-docs | jq .info.title
curl http://localhost:9090/api/v1/rules | jq .
curl http://localhost:3000/api/health
# Generar transacción y buscar trace
$body='{"accountId":"demo-acc-001","amount":10.00,"type":"DEBIT","currency":"MXN"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/transactions -Headers @{'Idempotency-Key'='obs-test';'X-Tenant-Id'='demo'} -ContentType 'application/json' -Body $body
# Luego buscar transaction_id en jaeger: http://localhost:16686
```
