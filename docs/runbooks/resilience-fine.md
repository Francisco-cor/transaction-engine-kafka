# Runbook — Resiliencia Fine y Bulkhead por Tópico (F4)

> F4: `bulkhead ledger 10 / fraud 10 / outbox 5`, `CB db 50%`, `pool ledger 10 fraud 10 tx 20`, `outbox backlog health`, `graceful drain 45s`.

## 1. Bulkhead por tópico

`services/ledger-service/src/main/resources/application.yml:72` (F4):

```yaml
resilience4j:
  bulkhead:
    instances:
      db: { maxConcurrentCalls: 10, maxWaitDuration: 100ms }
      ledger: { maxConcurrentCalls: 10, maxWaitDuration: 50ms }
      ledger-outbox: { maxConcurrentCalls: 5, maxWaitDuration: 50ms }
```

- `ledger` bulkhead aísla hot-account `SELECT FOR UPDATE` de `fraud` — si `ledger` satura, `fraud` sigue.
- Cuando `ledger` bulkhead lleno → `BulkheadFullException` → `503` + `Retry-After: 1` (queue shed 50).

Ver métricas:

```promql
resilience4j_bulkhead_available_concurrent_calls{instance="ledger-service"}
resilience4j_bulkhead_max_allowed_concurrent_calls
```

## 2. DB pool por servicio

`infra/docker-compose/docker-compose.yml:124` (F4):

- `transaction-service DB_POOL_MAX_SIZE 20` (ingesta)
- `ledger-service 10` (lock pesimista)
- `fraud-service 10`
- `reconciliation/notification 5`

Si `hikaricp_connections_pending >0` 60s → alerta `DbPoolExhausted` + readiness `DOWN` (outbox backlog).

Ver:

```bash
docker compose exec postgres psql -U postgres -d transactions -c "SELECT count(*) FROM pg_stat_activity"
curl http://localhost:8082/actuator/health/readiness | jq .
```

## 3. Outbox transactional

`spring.kafka.producer.transaction-id-prefix` vacío en local → non-transactional; con `KAFKA_TRANSACTION_ID_PREFIX=tx-` se activa `KafkaTransactionManager`.

Cuando activo, `OutboxPublisher.publishDueEvents` usa `Transactional` para `claim → send → markPublished` atómico (exactly-once producer). Consumidor `isolation.level=read_committed` no ve `PENDING` truncado.

Test:

```powershell
$env:KAFKA_TRANSACTION_ID_PREFIX="tx-test-"
docker compose up -d ledger-service
curl http://localhost:8082/actuator/health/readiness | jq .components.kafka
```

## 4. Health compuesto

`Ledger: OutboxBacklogHealthIndicator.java:11` + `transaction:OutboxBacklogHealthIndicator.java:11`:

- `readiness: readinessState,db,kafka,outbox` (`application.yml:54`).
- Si `outbox_pending >100` → `DOWN` (backpressure) — pod deja de recibir tráfico, drena backlog.

```bash
curl http://localhost:8082/actuator/health/readiness | jq .
# {"status":"DOWN","components":{"outbox":{"details":{"outbox_pending":120,"threshold":100}}}}
```

## 5. Graceful drain SIGTERM

- `infra/helm/umbrella/templates/deployment-*.yaml` `terminationGracePeriodSeconds: 45` + `preStop: sleep 10`.
- `spring.lifecycle.timeout-per-shutdown-phase: 30s` + `server.shutdown: graceful`.
- Kafka `MANUAL_IMMEDIATE` ACK post-commit: si SIGTERM durante `ledger.process`, no ACK → rebalance → otro pod redeliver → inbox `DUPLICATE`.

Ver drain:

```bash
kubectl rollout restart deployment/transaction-engine-ledger-service -n transaction-engine &
kubectl logs -f deployment/transaction-engine-ledger-service -n transaction-engine | grep "Graceful shutdown"
kubectl exec -it ledger-pod -- ps aux | grep java
# debe esperar 10s preStop + 30s drain
```

Local:

```powershell
docker compose kill --signal=SIGTERM ledger-service
docker compose logs ledger-service | Select-String "Shutting down"
```

## 6. DB/Kafka partition

**DB pause 15s (chaos/db-down.json):**

```bash
docker compose pause postgres && sleep 15 && docker compose unpause postgres
# ledger CB debe OPEN 30s luego HALF_OPEN 3 calls
curl http://localhost:8082/actuator/health | jq .components.db
```

**Kafka partition (Toxiproxy down):**

```bash
curl -X POST http://localhost:8474/proxies/kafka/toxics -d '{"name":"down","type":"down","stream":"downstream","toxicity":1.0}'
# consumers deben PAUSE sin perder offsets, luego drenar
```

## 7. Troubleshooting

- `bulkhead ledger DOWN` → escala `ledger` replicas o sube `maxConcurrentCalls 10→20` (ADR-009).
- `outbox backlog >100` → check `LedgerOutboxPublisher` `claim` + Kafka `acks=all` latency.
- `CB OPEN` → `resilience4j_circuitbreaker_state ==1` → espera 30s `waitDurationInOpenState`.

## 8. Verificación F4

```powershell
docker compose -f infra/docker-compose/docker-compose.yml config --quiet
curl http://localhost:8082/actuator/health/readiness | jq .
curl http://localhost:9090/api/v1/rules | jq .data.groups[].rules[].name
k6 run load-tests/k6-transactions.js --vus 20 # verifica no lost updates
```
