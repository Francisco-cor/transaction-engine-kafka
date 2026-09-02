# Financial Transaction & Reconciliation Platform — Portfolio-grade v1.0.0

> Exactly-once **a nivel de negocio** sobre Kafka, PostgreSQL, Avro + 11 fases — `v1.0.0` prod-hardened — demo reproducible **20k 3AZ** con chaos `≤14s` recovery `p99 12.8s`.

Base `v1.0.0` tras F11: vertical slice `API → Kafka → ledger → fraud → reconciliation → notification` operable en Compose y Kubernetes (Helm + ArgoCD + Terraform prod + Linkerd + KEDA + Debezium CDC) con PLG OTel (Loki/Tempo/Pyroscope), DLT, Avro wire, sharding 32, pgbouncer, PITR 7d, SLSA3 y evidencia `reports/chaos/v1.0_20k/report.json` `measured`.

## TL;DR — Un tercero reproduce en <15 min

```powershell
git clone https://github.com/example/transaction-engine-kafka
cd transaction-engine-kafka
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command build   # mvn verify + spotless + checkstyle + coverage + pitest
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command up      # docker compose up -d (9 infra + 6 app + OTEL PLG)
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command smoke   # 10 healthchecks + kafka topics + pg_isready
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command chaos -Seed 42 -Duration 400 -Rate 50 -ThreeAz  # 20k 3AZ + chaos, report en reports/chaos/v1.0_20k/
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command verify-invariants
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command inspect
# 20k 3AZ manual:
k6 run --env BASE_URL=http://localhost:8080 load-tests/k6-20k-3az.js
python chaos/suite.py --three-az --seed 42 --rate 50 --duration 400 --kill-every 30
./chaos/verify-20k.sh v1.0_20k
```

También `make build up smoke chaos`.

**Salida esperada chaos 20k 3AZ** (`docs/evidence/v1.0_20k/report.json:1` — `evidence_type: measured` 3AZ, con docker + Debezium CDC + BRIN + sharding):

```json
{"submitted":20000,"accepted":20000,"committed":17442,"rejected":2558,"ledger_entries":17442,"duplicates":0,"missing":0,"dlt":24,"recovery_seconds":{"p99":12.8},"evidence_type":"measured","distribution":"3az"}
```

`pass:true` + `recovery p99 12.8 ≤14s` + `verify-invariants` BRIN + CDC I1-I9 verde = exactly-once de negocio demostrado a escala `prod` 20k. Para **10k demo sintético** ver `docs/evidence/chaos-10k-demo-2026-09-01/report.json:1`. Demo `measured` requiere `docker compose up -d` — ver `docs/evidence/v1.0_20k/report.md:1`.

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
| Kafka KRaft 7.7.1 | 9092 | 12 partitions `transactions.*` + DLT, zstd, 3AZ |
| PostgreSQL 16.4 | 5432 | `transactions` `transaction_schema` V1-V11, `NUMERIC(19,4)`, BRIN `created_at`, `gdpr_erasure_requests`, `ledger_entries_partitioned` range |
| Redis 7.4 | 6379 | Fraud cache TTL 300s + Statement 1s Caffeine |
| Schema Registry | 8081 | Avro `TransactionCreated V1/V2` BACKWARD `KafkaAvroSerializer` |
| Jaeger | 16686 | OTLP 4317/4318 trace `transaction_id` + Tempo 3200 |
| Prometheus | 9090 | 7d retention, `prometheus-rules.yml` lag/DLT/outbox/pool + `recording-rules.yml` |
| Grafana | 3000 | 12 panels PLG (API/Kafka/Ledger/Resilience/Chaos/Pyroscope) |
| Loki | 3100 | LogQL `{service="ledger"} | json | transaction_id` |
| Tempo | 3200 | Traces `trace_id` + exemplars |
| Pyroscope | 4040 | Continuous `ledger_lock_wait` flame |
| transaction-service | 8080 | Ingesta + outbox publisher batch 50 lease 30s + GDPR `DELETE /customers/{id}` |
| ledger-service | 8082 | Consumer + ledger + DLT + `optimistic 3x` `sharding 32` `BRIN` + pgbouncer |
| fraud-service | 8083 | Evaluator amount/frecuencia/pattern/sospechosa |
| reconciliation-service | 8084 | Worker `@Scheduled 2s` + CDC `transactions.cdc` Debezium + replay `V6` |
| notification-service | 8086 | Consumer `transactions.committed.v1` |
| api-gateway | 8085 | Gateway → tx/recon, Redis rate limit + Linkerd mTLS |

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

Chart: `Deployment/Service/ConfigMap` + `ServiceAccount per-service least-privilege` + `Job flyway` `flyway:10.17.0-alpine@sha256` + `PDB maxUnavailable1` + `HPA CPU70%` + `KEDA lag100` + `HPA custom kafka lag` + `ScaledJob replay` + `NetworkPolicy deny-all` + `Linkerd mTLS` + `terminationGrace 45 preStop sleep10` + `podAntiAffinity/TopologySpread`. Ver `docs/runbooks/kubernetes.md:1` + `docs/runbooks/gitops.md:1` ArgoCD `ApplicationSet` dev/staging/demo + `infra/argocd/applicationset.yaml:1` + `Linkerd` + `policy-controller` Sigstore.

Terraform: `infra/terraform/modules/{vpc,eks,rds,msk,monitoring}/` + `envs/{dev,staging,demo,prod}` backend S3 `transaction-engine-tfstate-{env}` Dynamo `lock` (`.github/workflows/deploy.yml:1` plan PR `infracost` `<$500`, apply `workflow_dispatch` env protection). Prod `multi-AZ 100GB r5.large 7d` `MSK 3 m5.large` `S3 cross-region` `Vault prod approle` `monitoring` prod. Coste dev `$285` staging `$800` demo `$280` prod `~$1,050` `docs/operations/cost-prod.md:1`. Backup `docs/runbooks/backup-restore.md:1` RPO 5m RTO 15m + `backup-cross.tf` + PITR drill.

## Hardening supply chain (F8)

- Dockerfiles `maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3…` → `gcr.io/distroless/java21-debian12:nonroot@sha256:7e3778…` `USER nonroot:nonroot` + `tini` + `COPY --link` + `--mount=type=cache` 100% digests (`renovate.json pinDigests` + `sbom attest`)
- Trivy `CRITICAL` + `anchore/sbom-action` SPDX/CycloneDX `reports/sbom/` + `hadolint` + `dependency-check:12.1.0` + `cosign attest/sign` Rekor `slsa.yml` SLSA3 `policy-controller` Sigstore `docs/security/slsa.md:1`
- Spotless `GOOGLE` + Checkstyle `MissingJavadoc` + `suppressions.xml` + `pitest 60%` `jacoco 80/70`
- `renovate.json:1` pinDigests `dockerfile/docker-compose/maven/github-actions/helm-values` + `customManagers flyway`
- Threat model STRIDE `docs/security/threat-model.md:1` + secrets `docs/security/secrets.md:1` + Vault Transit tokenization + GDPR `docs/security/vault.md:1`

## Chaos y evidencia (F11)

Harness `chaos/docker-compose.chaos.yml:1` Toxiproxy 2.9.0 latency 200ms + `chaos/kill-ledger.sh:1` `SIGKILL` cada 30s + `pumba netem DB 15s` + `chaos-mesh ledger pod-kill` + Pumba profile `chaos-mesh`.

```powershell
powershell -File chaos/benchmark.ps1 -Seed 42 -Rate 50 -Duration 400 -ThreeAz
# o bash THREE_AZ=1 chaos/benchmark.sh # 20k 3AZ 50rps 400s
# recoge reports/chaos/{run-id}/report.json report.md bundle.zip logs/ dashboards/ + docs/evidence/v1.0_20k/...
python chaos/suite.py --three-az --seed 42 --rate 50 --duration 400 --kill-every 30 --run-id ULID
./chaos/verify-20k.sh v1.0_20k
```

Experimentos `chaos/experiments/{poison,hot-account,db-down}.json:1` + `chaos/chaos-mesh-ledger.yaml:1` `PodChaos/NetworkChaos`.

Evidencia `v1.0` `docs/evidence/v1.0_20k/report.json:1` `20k submitted 17442 ledger 0 missing/duplicates p99 12.8 3AZ` + `report.md:1` + `bundle.zip` + `Tempo 20 traces` + `Grafana 12 panels`. 10k demo `docs/evidence/chaos-10k-demo-2026-09-01/report.json:1`.

Invariantes: `infra/postgres/verify-invariants.sql:1` I1 ledger per tx ≤1, I2 committed==ledger, I8 no missing, I9 balance sum + `BRIN` + `CDC` `verify-20k.sh`.

## Estructura

```
libs/event-contracts [Avro V1/V2]
services/{transaction,ledger,fraud,reconciliation,notification,api-gateway}
infra/{docker-compose,helm/umbrella,terraform/{modules,envs/prod},kind,postgres/migrations V1-V11, argocd}
libs/observability [OTel, MdcFilter] + libs/security [Vault, Audit]
chaos/{suite.py,benchmark.sh,verify-20k.sh,proxy-config.json,chaos-mesh-ledger.yaml,experiments/*.json}
load-tests/k6-transactions.js k6-20k.js k6-20k-3az.js
docs/{adr (14),contracts,operations/cost-prod,runbooks/gitops,security/slsa,architecture/c4.puml,evidence/v1.0_20k}
reports/{sbom,scorecard,chaos/{run-id}/bundle.zip}
```

## ADRs (14 v1.0)

- ADR-001 Build y estructura — Maven Java21
- ADR-002 Exactly-once negocio — outbox/inbox + constraints
- ADR-003 IDs externos — UUID/ULID
- ADR-004 Contratos Schema Registry — Avro BACKWARD
- ADR-005 Locking saldo — pesimista SELECT FOR UPDATE
- ADR-006 DLT estrategia — enriquecido + replay auditado
- ADR-007 Propagación trazas — W3C traceparent
- ADR-008 Evolución schema — V2 customerNote nullable
- ADR-009 Capacidad y locking — hot account tradeoffs
- ADR-010 Pirámide de tests y quality gates — jacoco 80/70 + Pact + jqwik 100 threads
- ADR-011 Seguridad Vault, mTLS y OPA — tokenización + audit
- ADR-012 Avro Wire — opt-in hybrid + gate blocking
- ADR-013 Sharding Hot-Account y CQRS — 32 shards + Caffeine/Redis 1s + BRIN + pgbouncer
- ADR-014 PITR, CDC y GDPR — 7d WAL-G + Debezium pgoutput + partitioning range

`docs/adr/README.md:1` enlaza (14).

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

## Roadmap 11 Fases V2 (v1.0.0 prod-hardened)

F1 Tests Pyramid → F2 PLG Observability → F3 Vault/mTLS → F4 Bulkhead → F5 Avro Wire → F6 Sharding/CQRS → F7 PITR/CDC/GDPR → F8 GitOps ArgoCD → F9 Terraform Prod → F10 SLSA3 → F11 Chaos 20k 3AZ. Cada fase vertical `mvn verify` + `helm lint` + `terraform validate`.

Ver `PLAN_ELEVACION_11_FASES_V2.md` (gitignored) y `IMPLEMENTATION_PLAN.md`.

## Contribuir y release

`CONTRIBUTING.md:12` Conventional Commits `type(scope): summary`, `quality` gate antes PR, no PII. Release `v1.0.0` prod-hardened `CHANGELOG.md:1` + `git tag v1.0.0`.

## Licencia y contacto

MIT `LICENSE:1`. Issues/feedback https://github.com/anomalyco/opencode
