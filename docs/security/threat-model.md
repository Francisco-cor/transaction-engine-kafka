# Threat Model — Financial Transaction & Reconciliation Platform (STRIDE)

Estado: aceptado | Fecha: 2026-09-01 | Fase: 8 Supply Chain Hardening
Relacionado: docs/security/secrets.md, ADR-002 exactly-once, ADR-006 DLT, docs/adr/ADR-009-capacidad-y-locking.md

## 1. Alcance y activos

**Dentro del modelo:** `api-gateway` (JWT, rate limiting), `transaction-service` (idempotencia + outbox), `ledger-service` (lock pesimista + inbox dedup), `fraud-service` (Redis cache auxiliar), `reconciliation-service` (worker + replay auditado), `notification-service`, Kafka KRaft (6 particiones), PostgreSQL (fuente de verdad), Redis, Schema Registry, Observabilidad (Jaeger, Prometheus, Grafana).

**Activos críticos:** balance de cuentas `NUMERIC(19,4)`, `ledger_entries` append-only, `transactions` con `idempotency_scope + key` único, outbox/inbox, DLT con payloads, `fraud_decisions`, `notifications`, trazas con `transaction_id`.

**Fuera de alcance:** custodia real de dinero, KYC/AML, rails externos, secretos de producción (gestionados por External Secrets).

**Límites de confianza:** internet → api-gateway → servicios internos; Compose net interna; CI → imágenes → registro.

```
[Cliente] --TLS--> [api-gateway:8085 JWT+Bucket4j] --mTLS optional--> [transaction-service:8080]
  --> [Kafka transactions.created.v1 key=accountId] --> [ledger-service:8082 FOR UPDATE] --> [PostgreSQL]
                                                --> [fraud-service:8083 Redis 300s TTL] --> [PostgreSQL]
  --> [reconciliation-service:8084] --> [PostgreSQL] --> [admin replay SCOPE_admin:replay]
  --> [notification-service:8086] --> [FakeProvider/Webhook]
```

## 2. Suposiciones

- JWT issuer confiable en prod; en `local` profile `GATEWAY_SECURITY_ENABLED=false` con `X-Tenant-Id=demo` solo para dx.
- PostgreSQL es la única fuente de verdad financiera; Redis es descartable (TTL + fallback PG).
- Kafka at-least-once; exactly-once de negocio vía constraints + inbox/outbox, no exactly-once infra.
- Imágenes base pinneadas por digest y verificadas con Trivy/SBOM en CI; Renovate mantiene digests.

## 3. STRIDE por componente

| Componente | Spoofing | Tampering | Repudiation | Info Disclosure | DoS | Elevation |
|---|---|---|---|---|---|---|
| **api-gateway** | Tenant spoof `X-Tenant-Id` sin JWT → `SecurityConfig` exige `SCOPE_transactions:write`, `TenantOwnershipValidator` valida `accountId` pertenece a `tenant` (JWT `tenant` claim / `sub` prefix). | Body tampering → `request_hash` sha256 + 409 en mismatch idempotente; `customerNote` v2 tolerante pero no rompe. | Sin log de replay → `replay_audit` V6 + `dlt_replay_audit` V7 con `requested_by`, `reason`, `dry_run`. | PII en logs → `logback-spring.xml` MDC con redacción `authorization`, `DB_PASSWORD` vía `%replace`; `TransactionExceptionHandler` no expone stack. | Flood → Bucket4j+Redis 50 rps por tenant/IP → 429 + `Retry-After`; `max-request-size=64KB`, bulkhead Resilience4j. | JWT scope `admin:replay` vs `transactions:write`; `reconciliation-service` `@PreAuthorize`. |
| **transaction-service** | Idempotency-Key reutilizada cross-tenant → constraint único `(scope,key)` + 409. | Outbox doble publish → `outbox_events` lease/claim `claimed_by` UUID, `eventId` estable, consumers dedup por `inbox_events PK(consumer,event_id)`. | Cliente niega envío → `transactions` persiste `request_hash`, `created_at`, `correlationId`. | Balance enumeration → `GET /accounts/{id}/statement` exige ownership + paginación limitada 100. | Pool exhaustion → Hikari 20/5/30s, metrics `hikaricp_connections_pending`, `threat` tested en k6 50 rps hot keys. | Ownership bypass → `TenantOwnershipValidator` falla con 403 si `accountId` no pertenece a tenant. |
| **ledger-service** | Event spoof → Avro/JSON schema v1/v2 con `BACKWARD` + `FAIL_ON_UNKNOWN=false` pero `validateEvent` acepta solo `schemaVersion 1|2`. | Lost update hot account → `SELECT ... FOR UPDATE` + `lock_timeout 3s` + metric `ledger_lock_wait_seconds`, invariante balance sum verified `verify-invariants.sql` I1-I9. | DLT borrado → DLT topics retención 14d + `DeadLetterPublishingRecoverer` con `exception_class`, `failure_count`, `first_failure_at`, payload hash. | — | Hot account serializa throughput ~10-20 TPS/conta; ADR-009 evalúa optimistic `version` retry 3x como opt-in. | DLT replay sin auth → `DltReplayController` `@PreAuthorize(SCOPE_admin:replay)` + audit `requested_by`. |
| **Kafka** | Producer spoof → `acks=all`, `enable.idempotence=true`, `compression.type=zstd`, no `auto.create.topics`. | Partition tampering → `create-topics.sh` 6 particiones idempotente + `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`. | Offsets perdidos → ACK MANUAL_IMMEDIATE solo post-commit DB; `isolation.level=read_committed`. | Headers `traceparent` no llevan PII; `event_type`, `schema_version` minimal. | Thundering herd → outbox `baseBackoff*(1+jitter 0.2)`, `ExponentialBackOff(1000,2.0)`, circuit breaker `db` 50% /30s. | — |
| **PostgreSQL** | Migrator vs app user separación: `transaction_migrator` vs `transaction_app` mínimo privilegio `01-create-users.sh`. | Migración destrutiva → Flyway versionada V1-V8 + `verify-invariants.sql` en `verify-invariants` command. | Audit trail → `ledger_entries` nunca borrado, compensación vía nueva entrada. | `EXPOSE 5432` solo en compose local; prod vía RDS privado + `securityContext readOnlyRootFilesystem`. | `lock_timeout` 3s + `statement_timeout` 3s evita hold indefinido; `Pool 20` > `partitions*consumers`. | SQL injection → Spring JDBC `?` placeholders, sin concatenación. |
| **Redis** | Cache poisoning → TTL 300s, sin verdad financiera, fallback PG si excepción. | — | — | Password `REDIS_PASSWORD=redis_dev` solo `_dev`, rotado en prod. | Memory limit no fijado en compose; prod con `maxmemory` + eviction; metric `redis_exporter`. | — |
| **Imágenes/CI** | Base image tampering → FROM pinneado por `sha256` + `renovate.json` pinDigests; `hadolint` en CI. | Supply chain tampering → Trivy fs scan `CRITICAL` gate + OWASP `dependency-check:12.1.0` + SBOM SPDX/CycloneDX en `reports/sbom/` artefacto upload. | Build sin firma → cosign opcional documentado para K8s `verification` (no bloqueante local). | Secret leak → `.env` + `secrets/` gitignored, `helm values` External Secrets, `grep -i password` en `reports/` 0 hits gate. | CI DoS → `mvn verify` + `verify-invariants` + `load` k6 con seed fija. | CI privilege → `permissions: contents:read, security-events:write` minimal. |

## 4. Amenazas priorizadas y mitigaciones (top 7)

1. **Tenant spoofing (S)** — Prob Alta, Impacto Crítico → JWT resource server + `SCOPE_transactions:write` + ownership 403 + `verify tenant-isolation` test.
2. **Lost update hot account (T)** — Prob Alta → lock pesimista timeout + metric p95>100ms alerta + ADR-009 optimistic experimento.
3. **DoS por flood (D)** — Prob Media → Bucket4j 50rps + 429 + body limit 64KB + graceful shutdown 45s.
4. **DLT cementerio (R)** — Prob Media → DLT enriquecido + replay auditado + alerta `DLT rate>0`.
5. **Supply chain compromise (T)** — Prob Media → distroless nonroot `65532`, no shell, tini init, Trivy, SBOM, Renovate semanal.
6. **PII leak en logs (I)** — Prob Media → ECS JSON + MDC `transaction_id` sin PII, redaction `%replace(authorization)`.
7. **Secret en repo/imagen (I)** — Prob Baja pero Impacto Alto → `.env.example` `_dev` solo, `secrets/` gitignored, `docker inspect` y `helm template` checks, `reports/` grep 0.

## 5. Verificación

- `TransactionSecurityIntegrationTest` 401/403/429 + tenant isolation con `JwtDecoder` fake.
- `LedgerConcurrentBalanceIntegrationTest` 2 débitos mismo `accountId` → 1 commit 1 reject/lock wait.
- `verify-invariants` SQL I1/I2/I3/I8/I9 + `balance sum` en cada `chaos`/`load`.
- `trivy-results.sarif` + `reports/sbom/spdx.json` publicados en CI.
- `docker compose config --quiet` + `helm lint` + `hadolint` verdes.

## 6. Riesgo residual y próximos pasos

- Hot account limita throughput a ~20 TPS/cuenta caliente con lock 50ms; mitigar con sharding por hash o optimistic si `ledger_lock_wait_seconds p95>100ms` sostenido (K8s HPA KEDA lag>100 ya en F9).
- Distroless `HEALTHCHECK` no ejecuta `wget` (sin shell); health real es K8s `liveness /actuator/health/liveness, readiness /actuator/health/readiness` + compose healthcheck vía `api-gateway` HTTP.
- tini no es necesario si `shareProcessNamespace` en K8s, pero se mantiene para compose signal reap.
- Cosign firma opcional; próxima fase añade `policy-controller` verification.

## 7. Checklist Fase 8

- [x] Dockerfile distroless nonroot + tini + pinned digests
- [x] Trivy sin CRITICAL sin triage, SBOM SPDX/CycloneDX
- [x] Spotless + Checkstyle gate con Javadoc api
- [x] Renovate semanal con pinDigests
- [x] STRIDE documentado y `secrets.md` con checklist inspect/helm/logs
