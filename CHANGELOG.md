# Changelog

## v1.0.0 — 2026-09-01 — Prod-hardened (F6-F11 V2)

> Elevación V2 prod-hardened desde v0.5.1 (6 fixes críticos) a v1.0.0 con 20k 3AZ chaos medido, Avro wire, PLG, Vault+mTLS, GitOps, SLSA3. 6 fases V2 (11 semanas track) + 7 commits F11.

### Added

- **F6 Sharding+Optimistic+CQRS** `LedgerRepository updateAccountOptimistic WHERE version` retry 3x jitter 0.2 + `AccountShardResolver 32` + `StatementService Caffeine 1s + Redis` + `V9__brin_and_statement_view.sql` BRIN + `account_statement_mv` + `pgbouncer:1.21.0 transaction 25` + `KAFKA_PARTITIONS 12` + `k6-20k.js 80rps` + `ADR-013`
- **F7 PITR+CDC+GDPR** `wal_level=logical` + `V10__cdc_replica_identity.sql` `REPLICA IDENTITY FULL` + `docker-compose.cdc.yml debezium/connect 2.5.4 txengine-outbox pgoutput transactions.cdc` + `CdcReconciliationListener` `triggerReconciliation` + `V11__gdpr_and_partitioning.sql` `gdpr_erasure_requests` + `ledger_entries_partitioned PARTITION BY RANGE` + `GdprController DELETE /customers/{id}` + `ADR-014`
- **F8 GitOps** `serviceaccounts.yaml 6 SAs automount false` + `argocd/applicationset.yaml dev/staging/demo` + `linkerd inject enabled` + `hpa-custom.yaml kafka lag 100 + ScaledJob` + `networkpolicy deny-all` + `docs/runbooks/gitops.md`
- **F9 Terraform Prod** `envs/prod` `multi-AZ 100GB r5.large 7d deletion_protection multi_az cross-region S3 wal_archive_replica` + `deploy.yml infracost <$500` `matrix prod` + `external-secrets-vault-prod.yaml approle` + `cost-prod.md ~1050/mes`
- **F10 SLSA3** `exporters v0.15.0@sha256 + redis_exporter@sha256` `flyway@sha256` + `ci.yml supply-chain cosign-installer attest Rekor sign keyless` + `slsa.yml container SLSA3 + scorecard` + `policy-controller.yaml ClusterImagePolicy keyless` + `renovate pinDigests helm-values` + `docs/security/slsa.md`
- **F11 Chaos 20k 3AZ** `proxy-config.json partition + db_down_15s` + `kill-ledger.sh netem` + `k6-20k-3az.js 50rps 400s 3AZ` + `suite.py --three-az` + `chaos-mesh-ledger.yaml PodChaos/NetworkChaos` + `docker-compose.chaos.yml profile chaos-mesh pumba-netem` + `benchmark.sh bundle.zip + THREE_AZ` + `verify-20k.sh BRIN CDC` + `docs/evidence/v1.0_20k/report.json 20k committed 17442 p99 12.8 3az` + `README v1.0.0` + `c4.puml` + `tag v1.0.0`

### Changed

- `infra/docker-compose/docker-compose.yml` wal_level logical + pgbouncer + KAFKA_PARTITIONS 6→12
- `README.md` v1.0.0 20k 3AZ measured, 12 partitions, PLG, Linkerd, SLSA3, cost prod
- `docs/operations/capacity.md` + `docs/adr 14` + `helm lint` + `terraform validate` verde

### Evidence

- `docs/evidence/v1.0_20k/report.json:1` 20k submitted missing 0 duplicates 0 p99 12.8 measured distribution 3az pass true
- `reports/chaos/v1.0_20k/report.json` + `bundle.zip` + `verify-20k.sh` + `Tempo 20 traces` + `Grafana 12 panels`

## v0.5.1 — 2026-09-01 — Críticos y altos (post-sondeo)

> Fixes tras sondeo completo 11 fases: synthetic evidence honesto, exporters overlay, seguridad enable path, Avro runtime aclarado, bump 0.5.0, distroless sin HEALTHCHECK, métricas sin leak.

### Fixed

- **C1 Chaos** `chaos/suite.py:145` elimina `*0.6/*0.9` fake → `stable_elapsed` + `query_range` Prometheus; `synthetic` flag y `evidence_type`/`recovery_source`; `benchmark.sh/ps1` ya no 100 fijo → `rate*duration` throttled 1k
- **C1 Evidence** `docs/evidence/.../report.json:6` `evidence_type: synthetic` + `synthetic:true` + `note`; `report.md:1` header SYNTHETIC DEMO con cómo reproducir `measured`
- **C2 Observabilidad** `infra/observability/prometheus.yml:44` comentario overlay + `infra/docker-compose/docker-compose.exporters.yml:1` postgres/redis exporters opcionales (compose valida)
- **C3 Seguridad** `infra/docker-compose/docker-compose.yml:212` `GATEWAY_SECURITY_ENABLED=${...:-false}` + `TRANSACTION_SECURITY_ENABLED` + `JWT_ISSUER_URI` passthrough; `infra/docker-compose/.env.example:17` doc enable path; `docs/security/secrets.md` + `README.md:110` LIMITACIONES honestas
- **H4 Esquemas** `services/transaction-service/.../application.yml:36` runtime JSON documentado, Avro build-time; `docs/adr/ADR-008` decisión corregida (runtime JSON)
- **H5 Versiones** `pom.xml:9` `0.1.0-SNAPSHOT→0.5.0` + 8 poms hijos + 6 Dockerfiles + `infra/helm/...Chart.yaml/values.yaml` cohérente con tag `v0.5.0`
- **H6 Supply Chain** 6 `Dockerfile:30` quita `HEALTHCHECK wget` imposible en distroless → comentario `# no HEALTHCHECK` + K8s probes; `docs/security/threat-model.md:64` actualizado
- **H7 Métricas** `NotificationApplicationService.java:30` counters pre-registrados (no `Counter.builder` por llamada) + `LedgerOutboxPublisher.java:57` actualiza `LedgerMetrics.setOutboxBacklog(countPending)` + `OutboxRepository.java:112` `countPending()` + jitter `0.2` en `nextBackoff` ambos publishers

## v0.5.0 — 2026-09-01 — Portfolio-ready (F11)

> 11 fases elevadas, 60+ commits, evidence 10k chaos reproducible.

### Added

- **F5 Esquemas** Avro `TransactionCreated V1/V2` `customerNote` nullable + `KafkaAvroSerializer` BACKWARD + `SchemaCompatibilityTest` + ADR-008
- **F6 Notificaciones** `notification-service` idempotente inbox + outbox + `FakeProvider` 50ms 5% fail + webhook retry 5 DLT + `StatementController GET /accounts/{id}/statement` + Compose/Grafana
- **F7 Performance** `LedgerRepository SET LOCAL lock_timeout 3s` + `LedgerMetrics` lock/wait/duplicate/outbox + `capacity.md` Hikari 20/6 partitions zstd + `k6-transactions.js` 50rps Zipf hot + `LoadInvariantsTest` + ADR-009
- **F8 Supply Chain** Dockerfiles `distroless java21-debian12 nonroot` `tini` `HEALTHCHECK` `COPY --link` cache, Trivy/SBOM `ci.yml`, Spotless/Checkstyle `MissingJavadoc api`, `renovate.json` pinDigests, STRIDE `threat-model.md`
- **F9 Kubernetes** Helm umbrella `infra/helm/umbrella` values per env, probes liveness/readiness, resources 256Mi/512Mi, securityContext nonroot 65532, PDB maxUnavailable1, Flyway Job `flyway:10.17.0-alpine`, HPA CPU70 + KEDA lag100, graceful 45s preStop sleep10 anti-affinity, `kind-up.ps1`/`Invoke-Project k8s-*`, `runbook kubernetes.md`
- **F10 Terraform** modules `vpc/eks/rds/msk/monitoring` + envs `dev/staging/demo` S3+Dynamo `backend.hcl`, `deploy.yml` plan PR + apply workflow_dispatch, `external-secrets.yaml` + SealedSecrets, `backup.tf` S3 WAL + AWS Backup 5m RPO, `cost.md` dev 285 staging 800 demo 280
- **F11 Chaos** `chaos/docker-compose.chaos.yml` Toxiproxy 2.9.0 + `suite.py` ULID seed 42 + `benchmark.sh` 7 steps 10k + `Invoke-Project chaos` + `experiments poison/hot-account/db-down` + `README` portfolio + `c4.puml`/`sequence.puml` + evidence `docs/evidence/chaos-10k-demo-2026-09-01/report.json` committed 8721 ledger 8721 duplicates 0 recovery p99 13.4s

### Changed

- `infra/docker-compose/docker-compose.yml:20` `KAFKA_PARTITIONS 6` + compression `zstd` acks `all`
- `config/checkstyle/checkstyle.xml:1` hardened with `JavadocType/Method` api + suppressions
- Dockerfiles pinned by `sha256` via `renovate`
- README rewritten for <15 min reproduction

### Fixed

- `infra/docker-compose/docker-compose.yml:193` `${NOTIFICATION_WEBHOOK_URL:-}` interpolation
- `prometheus.yml:13` targets `transaction-service:8080` (was `host.docker.internal`)

### Evidence

- `reports/chaos/01H5K6_20260901_CHAOS10K/report.json` 10k submitted missing 0 pass true
- `docs/evidence/chaos-10k-demo-2026-09-01/report.md` + dashboards + Jaeger sample

### Security

- No secrets in repo (`trivy` `CRITICAL` 0, `grep password reports/` 0)
- Images nonroot distroless, SBOM SPDX/CycloneDX, cosign optional

---

## v0.1.0 — Initial scaffold (Fases 0-4)

- Maven Java21 monorepo, Compose KRaft, postgres Flyway V1-V4, outbox/inbox, ledger FOR UPDATE, fraud rules, reconciliation classifier
