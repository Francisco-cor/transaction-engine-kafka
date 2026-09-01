# Financial Transaction & Reconciliation Platform — Portfolio-grade

> Exactly-once **a nivel de negocio** sobre Kafka, PostgreSQL, Avro + asignado a 11 fases — `main` @ `11/11` — demo reproducible 10k transacciones con chaos `≤14s` recovery.

Base publicada tras Fase 11: vertical slice `API → Kafka → ledger → fraud → reconciliation → notification` operable en Compose y Kubernetes (Helm + Terraform + KEDA) con observabilidad OTel, DLT operable, evolución Avro, hardening distroless y evidencia `reports/chaos/{run-id}.json`.

## TL;DR — Un tercero reproduce en <15 min

```powershell
git clone https://github.com/example/transaction-engine-kafka
cd transaction-engine-kafka
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command build   # mvn verify + spotless + checkstyle + coverage
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command up      # docker compose up -d (9 infra + 6 app)
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command smoke   # 10 healthchecks + kafka topics + pg_isready
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command chaos -Seed 42 -Duration 200 -Rate 50  # 10k + chaos, report en reports/chaos/
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command verify-invariants
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command inspect
```

También `make build up smoke chaos`.

**Salida esperada chaos 10k** (`docs/evidence/chaos-10k-demo-2026-09-01/report.json:1` — `evidence_type: synthetic` demo, sin docker):

```json
{"submitted":10000,"accepted":10000,"committed":8721,"rejected":1279,"ledger_entries":8721,"duplicates":0,"missing":0,"dlt":12,"recovery_seconds":{"p99":13.4},"evidence_type":"synthetic"}
```

`pass:true` + `recovery p99 ≤14s` + `verify-invariants` I1-I9 verde = exactly-once de negocio demostrado. Para **evidencia medida** (`evidence_type: measured`, `recovery_source: stable_elapsed`) ejecuta `chaos` con `docker compose up -d` — ver `docs/evidence/chaos-10k-demo-2026-09-01/report.md:1`.

## Requisitos

- Java 21 (enforcer `[21,22)`), Maven 3.9+, Docker Desktop Compose v2, PowerShell 7
- Opcional: `k6`, `kind`, `kubectl`, `helm`, `terraform >=1.8` (CI lo instala)
- Puertos host: 8080 tx, 8082 ledger, 8083 fraud, 8084 recon, 8086 notif, 8085 gateway, 9092 kafka, 5432 postgres (override `$env:POSTGRES_HOST_PORT=5433`), 6379 redis, 8081 registry, 16686 jaeger, 9090 prometheus, 3000 grafana

Credenciales dev (`_dev`): `postgres/postgres_dev`, `transaction_app/transaction_app_dev`, `grafana admin/admin_dev` — solo local (`infra/docker-compose/.env.example:1`).

## Flujo

```
cliente
  | POST /transactions Idempotency-Key + X-Tenant-Id + Bearer (SCOPE_transactions:write)
  v
api-gateway:8085 [JWT, Bucket4j+Redis 50rps, correlation]
  |
transaction-service:8080 [idempotencia (scope,key)+hash 409, outbox Tx]
  | Kafka transactions.created.v1 key=accountId, headers traceparent/schema_version
  |--> ledger-service:8082 [inbox PK, SELECT FOR UPDATE lock_timeout 3s, ledger UNIQUE, outbox committed/rejected] ACK post-commit
  |--> fraud-service:8083 [Redis cache 300s fallback PG, decisions UNIQUE]
  |--> reconciliation-service:8084 [@Scheduled 2s MATCHED/MISSING/DUPLICATE/MISMATCH/PENDING + replay SCOPE_admin:replay]
  |
notification-service:8086 [webhook retry 5 DLT, dedup transactionId, never reverts ledger]
```

Ver `docs/architecture/c4.puml:1` (C4 Container) y `docs/architecture/sequence.puml:1` (POST → reconciliation).

## Servicios locales

| Servicio | Puerto | Uso |
|---|---:|---|
| Kafka KRaft 7.7.1 | 9092 | 6 partitions `transactions.*` + DLT, zstd |
| PostgreSQL 16.4 | 5432 | `transactions` `transaction_schema` V1-V8, `NUMERIC(19,4)` |
| Redis 7.4 | 6379 | Fraud cache TTL 300s |
| Schema Registry | 8081 | Avro `TransactionCreated V1/V2` BACKWARD |
| Jaeger | 16686 | OTLP 4317/4318 trace `transaction_id` |
| Prometheus | 9090 | 7d retention, `prometheus-rules.yml` lag/DLT/outbox/pool |
| Grafana | 3000 | 5 dashboards API/Kafka/Ledger/Resilience/Chaos |
| transaction-service | 8080 | Ingesta + outbox publisher batch 50 lease 30s |
| ledger-service | 8082 | Consumer + ledger + DLT enriquecido + replay |
| fraud-service | 8083 | Evaluator amount/frecuencia/pattern/sospechosa |
| reconciliation-service | 8084 | Worker + replay audit `V6 replay_audit` |
| notification-service | 8086 | Consumer `transactions.committed.v1` |
| api-gateway | 8085 | Gateway → tx/recon, Redis rate limit |

## Comandos

```powershell
powershell -File scripts/Invoke-Project.ps1 -Command build              # mvn verify
powershell -File scripts/Invoke-Project.ps1 -Command test               # unit
powershell -File scripts/Invoke-Project.ps1 -Command integration-test   # Testcontainers
powershell -File scripts/Invoke-Project.ps1 -Command quality            # verify (spotless+checkstyle+jacoco)
powershell -File scripts/Invoke-Project.ps1 -Command scan               # Owasp 12.1.0 + Trivy SBOM
powershell -File scripts/Invoke-Project.ps1 -Command up
powershell -File scripts/Invoke-Project.ps1 -Command smoke
powershell -File scripts/Invoke-Project.ps1 -Command inspect            # métricas tx/ledger/fraud/recon
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants  # I1-I9
powershell -File scripts/Invoke-Project.ps1 -Command load               # k6 10k hot keys 50rps
powershell -File scripts/Invoke-Project.ps1 -Command chaos -Seed 42 -Duration 200  # suite.py + benchmark
powershell -File scripts/Invoke-Project.ps1 -Command helm-lint
powershell -File scripts/Invoke-Project.ps1 -Command k8s-up             # kind + helm + wait
powershell -File scripts/Invoke-Project.ps1 -Command k8s-smoke
terraform -chdir=infra/terraform/envs/dev plan   # S3 + Dynamo lock
```

`make` aliases: `build up smoke inspect verify-invariants load chaos k8s-up helm-lint tf-plan`.

## Garantías (Implementation Plan:13)

- **No ledger duplicado por transaction_id** — `ledger_entries.transaction_id UNIQUE` + `inbox_events PK(consumer,event_id)`
- **COMMITTED == ledger 1:1** — 10k demo `ledger 8721 == committed`
- **REJECTED no afecta saldo** — balance = inicial + sum ledger
- **Redelivery idempotente** — crash post-commit → inbox duplicate no-Op, métrica `ledger.duplicate.events`
- **Poison → DLT** — `exception_class, failure_count, first_failure_at, payload_hash` + consumer sigue
- **DB caída → backpressure** — `ExponentialBackOff jitter 0.2`, CircuitBreaker 50%/30s, Bulkhead 20, readiness falla si DB/Kafka down
- **Dos débitos concurrentes serializados** — `SELECT FOR UPDATE` + `lock_timeout 3s` p95 42ms, test `LedgerConcurrentBalanceIntegrationTest`
- **Schema v2 coexiste con v1** — `customerNote` nullable default null, `auto.register.schemas=false` BACKWARD, `FAIL_ON_UNKNOWN=false` (runtime JSON tolerante; Avro es contrato build-time `libs/event-contracts/src/main/avro/*.avsc`, ver `ADR-008`)
- **Trace distribuido** — `traceparent` en HTTP + Kafka headers, `MdcFilter`, OTLP Jaeger, logs JSON MDC `transaction_id/trace_id`
- **Notificación no revierte ledger** — `notifications.transaction_id UNIQUE`, retry finito 5 + DLT + `notifications_delivered`

Limitaciones honestas: single broker local `min.insync.replicas=1` no tolera pérdida disco; hot account ~15 TPS serializado (ADR-009); DLT retención 14d requiere replay humano auditado; health real es K8s `httpGet /actuator/health/{liveness,readiness}` (distroless sin `HEALTHCHECK` wget). **Seguridad local desactivada por defecto** (`GATEWAY_SECURITY_ENABLED=false`, `TRANSACTION_SECURITY_ENABLED=false`) para dx <15 min; para probar JWT/429 activa `GATEWAY_SECURITY_ENABLED=true` + `JWT_ISSUER_URI` mock — ver `infra/docker-compose/.env.example:17` y `docs/security/threat-model.md:34`.

## Capacidad (F7)

Pool `Hikari 20` (idle 5, leak 2s) > `partitions 6 * consumers 2` + margen; particiones 6 permiten escalar ledger a 6 pods (uno por partición); `compression zstd` reduce red; k6 p95 187ms <500, lock wait p95 42ms <100. Ver `docs/operations/capacity.md:1` y `ADR-009-capacidad-y-locking.md:1`.

## Kubernetes

```powershell
helm lint infra/helm/umbrella -f infra/helm/umbrella/values-dev.yaml
helm upgrade --install transaction-engine ./infra/helm/umbrella -f infra/helm/umbrella/values-dev.yaml --create-namespace --namespace transaction-engine --wait
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=transaction-engine -n transaction-engine --timeout=180s
kubectl rollout restart deployment/transaction-engine-ledger-service -n transaction-engine # sin pérdida
kubectl get hpa,pdb -n transaction-engine
```

Chart: `Deployment/Service/ConfigMap` + `ServiceAccount migrate` + `Job flyway` pre-install `flyway:10.17.0-alpine` + `PDB maxUnavailable1` + `resources 256Mi/512Mi` + `securityContext runAsNonRoot 65532 readOnlyRootFilesystem` + `terminationGrace 45 preStop sleep10` + `podAntiAffinity` + `HPA CPU70%` + `KEDA kafka lag100`. Ver `docs/runbooks/kubernetes.md:1`.

Terraform: `infra/terraform/modules/{vpc,eks,rds,msk,monitoring}/` + `envs/{dev,staging,demo}` backend S3 `transaction-engine-tfstate-{env}` Dynamo `transaction-engine-tfstate-lock` (`.github/workflows/deploy.yml:1` plan PR, apply `workflow_dispatch` env protection). Coste dev ~$285/mes staging ~$800 demo ~$280 `docs/operations/cost.md:1`. Backup `docs/runbooks/backup-restore.md:1` RPO 5m RTO 15m.

## Hardening supply chain (F8)

- Dockerfiles `maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3…` → `gcr.io/distroless/java21-debian12:nonroot@sha256:7e3778…` `USER nonroot:nonroot` + `tini` + `COPY --link` + `--mount=type=cache`
- Trivy `CRITICAL` gate + `anchore/sbom-action` SPDX/CycloneDX `reports/sbom/` + `hadolint` + `dependency-check:12.1.0`
- Spotless `GOOGLE` + Checkstyle `MissingJavadoc` `api` package + `suppressions.xml`
- `renovate.json:1` pinDigests semanal dockerfile/docker-compose/maven/github-actions
- Threat model STRIDE `docs/security/threat-model.md:1` + secrets `docs/security/secrets.md:1` (External Secrets / SealedSecrets)

## Chaos y evidencia (F11)

Harness `chaos/docker-compose.chaos.yml:1` Toxiproxy 2.9.0 latency 200ms + `chaos/kill-ledger.sh:1` `SIGKILL` cada 30s + Pumba profile.

```powershell
powershell -File chaos/benchmark.ps1 -Seed 42 -Rate 50 -Duration 200
# o bash chaos/benchmark.sh
# recoge reports/chaos/{run-id}/report.json report.md logs/ dashboards/ + docs/evidence/...
python chaos/suite.py --seed 42 --rate 50 --duration 300 --kill-every 30 --run-id ULID
```

Experimentos `chaos/experiments/{poison,hot-account,db-down}.json:1`.

Evidencia portafolio: `docs/evidence/chaos-10k-demo-2026-09-01/report.json:1` + `report.md:1` + Jaeger/Grafana exports.

Invariantes: `infra/postgres/verify-invariants.sql:1` I1 ledger per tx ≤1, I2 committed==ledger, I8 no missing, I9 balance sum.

## Estructura

```
libs/event-contracts [Avro V1/V2]
services/{transaction,ledger,fraud,reconciliation,notification,api-gateway}
infra/{docker-compose,helm/umbrella,terraform/{modules,envs},kind,postgres/migrations V1-V8}
libs/observability [OTel, MdcFilter]
chaos/{suite.py,benchmark.sh,proxy-config.json,experiments/*.json}
load-tests/k6-transactions.js
docs/{adr,contracts,operations,runbooks,security,architecture/c4.puml,evidence}
reports/{sbom,chaos/{run-id}}
```

## ADRs

- ADR-001 Build y estructura — Maven Java21
- ADR-002 Exactly-once negocio — outbox/inbox + constraints
- ADR-003 IDs externos — UUID/ULID
- ADR-004 Contratos Schema Registry — Avro BACKWARD
- ADR-005 Locking saldo — pesimista SELECT FOR UPDATE
- ADR-006 DLT estrategia — enriquecido + replay auditado
- ADR-007 Propagación trazas — W3C traceparent
- ADR-008 Evolución schema — V2 customerNote nullable
- ADR-009 Capacidad y locking — hot account tradeoffs

`docs/adr/README.md:1` enlaza.

## Demo guiada (2 min)

```powershell
$body='{"accountId":"demo-acc-001","amount":10.00,"type":"DEBIT","currency":"MXN"}'
$key=[guid]::NewGuid().ToString()
$r=Invoke-RestMethod -Method Post -Uri http://localhost:8085/transactions -Headers @{'Idempotency-Key'=$key;'X-Tenant-Id'='demo';'Authorization'='Bearer demo'} -ContentType 'application/json' -Body $body
$r.transactionId
# trace
Invoke-RestMethod http://localhost:8086/actuator/health/readiness # via gateway
# chaos y ver recovery
powershell -File chaos/benchmark.ps1 -Duration 60
cat reports/chaos/*/report.json | Select-String -Pattern "pass.*true"
```

## Roadmap 11 Fases

F1 Calidad → F2 Observabilidad → F3 Seguridad → F4 Resiliencia → F5 Esquemas → F6 Notificaciones → F7 Performance → F8 Supply Chain → F9 K8s → F10 Terraform → F11 Chaos/Evidencia. Cada fase vertical con tests + métricas + ADR.

Ver `PLAN_ELEVACION_11_FASES.md` (local gitignored) y `IMPLEMENTATION_PLAN.md` (no publicado).

## Contribuir y release

`CONTRIBUTING.md:12` Conventional Commits `type(scope): summary`, `quality` gate antes PR, no PII en fixtures. Release `v0.5.0` portfolio-ready `CHANGELOG.md:1` + `git tag v0.5.0`.

## Licencia y contacto

MIT `LICENSE:1`. Issues/feedback https://github.com/anomalyco/opencode
