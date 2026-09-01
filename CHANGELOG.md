# Changelog

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
