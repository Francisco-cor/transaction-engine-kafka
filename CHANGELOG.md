# Changelog

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
