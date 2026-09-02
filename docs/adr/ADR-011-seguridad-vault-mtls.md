# ADR-011: Seguridad Vault, mTLS y OPA

- Estado: aceptado
- Fecha: 2026-09-01
- Relacionado: ADR-006 DLT, ADR-007 trazas, docs/security/threat-model.md, docs/security/vault.md
- Superseeds: `docs/security/secrets.md` local `*_FILE` → Vault prod

## Contexto

`v0.5.1` tenía `GATEWAY_SECURITY_ENABLED=false` local, `ExternalSecrets` solo ejemplo, `customerNote` 256 plain en `transactions.created.v1`, y `RateLimitFilter` solo in-memory. Para prod necesitamos KV real, tokenización PII, mTLS y OPA.

## Decisión

1. **Vault:** `infra/docker-compose/docker-compose.vault.yml:6` Vault `1.15.6` dev + `vault-init` con `kv-v2 secret/transaction-app` + `transit/keys/customer-note`. `infra/k8s/external-secrets-vault.yaml:11` `ClusterSecretStore vault-backend` + `ExternalSecret` 1h. `VAULT_ADDR` vacío en local → fallback plain (dx).
2. **Tokenización:** `libs/security/VaultTransitClient.java:24` `encrypt`/`decrypt` via `transit/encrypt/customer-note` con `X-Vault-Token`. `TransactionApplicationService.java:186` tokeniza `customerNote` si `VaultTransitClient.isEnabled()`, guarda `customerNoteVault: vault|plain` en `eventMap`.
3. **mTLS + NetworkPolicy:** `infra/helm/umbrella/templates/networkpolicy.yaml:1` `deny-all` + allow `ledger→postgres:5432` + `gateway→transaction:8080` + `kafka 29092`. `infra/k8s/networkpolicy.yaml` + `cert-manager` `selfsigned-issuer` + `Certificate ledger-mtls`. Gateway nota mTLS via `X-Forwarded-Client-Cert` (Linkerd en K8s).
4. **OPA:** `infra/opa/policy.rego:1` `data.transaction.allow` con `has_scope` para `transactions:write/read` y `admin:replay`. Gateway `OPA sidecar` (F3-2) evalúa, `ReconciliationController` mantiene `@PreAuthorize`.
5. **Audit:** `libs/security/AuditLogger.java:11` `AUDIT` logger JSON a Loki con `audit_action/audit_transaction_id/audit_requested_by` MDC. `ReconciliationApplicationService.java:76` y `DltReplayService.java:32` usan `audit.logReplay/logDltReplay`. Loki `limits_config.retention 720h` (30d) para `service="audit"`.
6. **Rate limit + WAF:** Gateway Bucket4j+Redis 50rps distribuido (ya), `transaction-service` `WafBodyLimitFilter.java:11` `413` si `Content-Length >64KB` + `RateLimitFilter` local `Bucket4j 50rps` como segunda línea. `application.yml` ya tiene `multipart 64KB` + `max-http-form-post-size 64KB`.

## Alternativas

- **Vault Agent sidecar:** descartado por complejidad local; `ExternalSecrets` más simple para K8s y `VAULT_ADDR` env para compose dev.
- **SealedSecrets solo:** sin rotación 90d; Vault transit da `rotate` y `exportable false`.
- **OPA bundle vs sidecar:** sidecar en gateway para baja latencia; bundle centralizado en Fase 4 si necesario.

## Consecuencias

- `customerNote` con `vault:v1:...` solo descifrable con `VAULT_TOKEN` — si Vault down, fallback `plain` y `metadata.vault=plain` (visible en `verify-invariants`).
- `NetworkPolicy deny-all` bloquea `postgres` desde `fraud` no listado — debe allowlist `fraud→postgres` si fraud lee PG (ya añadido).
- `AuditLogger` logs van a `loki:3100` con `service="audit"` y retención 30d, separada de 7d default.
- CI debe pasar `docker compose -f compose.yml -f compose.vault.yml config --quiet`.

## Validación

- `docker compose -f infra/docker-compose/docker-compose.yml -f infra/docker-compose/docker-compose.vault.yml up -d vault` + `curl http://localhost:8200/v1/sys/health`.
- `VAULT_ADDR=http://localhost:8200 VAULT_TOKEN=root mvn test -Dtest=VaultTransitClientTest` (próximo).
- `helm template infra/helm/umbrella -f values.yaml | grep NetworkPolicy` muestra `deny-all`.
- `opa test infra/opa/policy.rego -v` con `conftest`.

## Referencias

- `infra/docker-compose/docker-compose.vault.yml`, `infra/k8s/external-secrets-vault.yaml`, `libs/security/*`, `infra/opa/policy.rego`.
