# Secrets Handling — Transaction Engine

Estado: aceptado | Fase: 8 hardening | Rel: docs/security/threat-model.md, infra/docker-compose/.env.example, .gitignore:11

## Local

- `infra/docker-compose/.env` is gitignored (`.gitignore:11`). Copy from `.env.example` and never commit. `.env.example` values end with `_dev` and are not production.
- `infra/docker-compose/secrets/` is also gitignored; preferred for Swarm/K8s is Docker secrets files per service:

  ```yaml
  secrets:
    postgres_app_password:
      file: ./secrets/postgres_app_password.txt
  services:
    transaction-service:
      secrets: [postgres_app_password]
      environment:
        POSTGRES_APP_PASSWORD_FILE: /run/secrets/postgres_app_password
  ```

  Compose reads `*_FILE` via entrypoint; alternative is `env_file` + `secrets` (no plain `-e DB_PASSWORD` visible in `docker inspect` without swarm).
- Check plaintext exposure in compose:

  ```powershell
  docker compose -f infra/docker-compose/docker-compose.yml config | Select-String "PASSWORD|SECRET|TOKEN"
  # Debe mostrar solo *_FILE o variables con _dev en local
  ```

## Imágenes y Manifests

- No secret in `Dockerfile`, `application.yml`, `deployment.yaml`, `helm values`. Dockerfile pinned by digest (`FROM ...@sha256:...` + `renovate.json`).
- `application.yml` uses `${DB_PASSWORD:transaction_app_dev}` placeholders; prod overrides via env.
- `helm values` use `ExternalSecrets` or `SealedSecrets`:

  ```yaml
  # infra/k8s/external-secrets.yaml (ejemplo F10)
  apiVersion: external-secrets.io/v1beta1
  kind: ExternalSecret
  spec:
    secretStoreRef: {name: vault-backend, kind: ClusterSecretStore}
    target: {name: transaction-app-password}
    data: [{secretKey: password, remoteRef: {key: prod/transaction_app}}]
  ```

- CI uses `secrets` from GitHub Environments, not `env`. Workflow `permissions: contents:read, security-events:write` minimal.

## Logs y PII

- `libs/observability/MdcFilter.java` + `logback-spring.xml` via `observability` lib includes MDC `trace_id`, `span_id`, `transaction_id`, `account_id` but never logs `Authorization`, `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT`. Config uses `%replace` for redaction:

  ```xml
  <pattern>%d{ISO8601} %-5level [%thread] %logger{36} traceId=%X{traceId:-} %replace(%msg){'password[^,]*','***'}%n</pattern>
  ```

- `TransactionExceptionHandler` masks messages: only returns safe `ApiError` without stack.
- Search for leaks (gate in PR):

  ```powershell
  grep -R -i "password\|secret\|authorization" logs/ reports/ || echo "0 hits"
  grep -R "_dev" infra/helm --include="*.yaml" | grep -v "example" && echo "FAIL leaked _dev" || echo "OK"
  ```

## Rotación

- Dev secrets in `.env.example` end with `_dev` and are not production.
- For prod: `transaction_app` / `transaction_migrator` / `redis_dev` / `grafana admin` must be overridden via env / External Secrets.
- Rotation drill: quarterly `terraform apply -replace` for RDS password + `ExternalSecret` refresh; document in `docs/runbooks/secrets-rotation.md`.
- Revocation: if secret committed, `git filter-repo` + rotate immediately, do not `git revert` alone.

## Supply Chain Secrets

- SBOM `reports/sbom/spdx.json` must not contain credentials; verify `grep -i password reports/sbom/`.
- Trivy scan `trivy-results.sarif` uploaded to GitHub Security; no `CRITICAL` without triage.
- Renovate `pinDigests: true` ensures base image digest is explicit; PR with new digest must be reviewed for provenance (SLSA).

## Checklist Fase 8 (verificable)

- [ ] `docker inspect $(docker ps -q -f name=transaction-service) | grep -i password` shows no plain `DB_PASSWORD` (usa `*_FILE` en producción).
- [ ] `helm template transaction-engine ./infra/helm/umbrella -f values-dev.yaml | grep -i password` no muestra `..._dev` en prod values.
- [ ] `grep -R -i "password\|secret" reports/ --include="*.json" --include="*.log"` 0 hits.
- [ ] `grep -R "BEGIN PRIVATE KEY\|BEGIN RSA PRIVATE KEY" .` 0 hits.
- [ ] `git log --all -p | grep -i "postgres_dev\|transaction_app_dev"` only in `.env.example`.
- [ ] `trivy fs --severity CRITICAL --exit-code 1 .` paso sin CRITICAL.
- [ ] `ls -lh reports/sbom/spdx.json reports/sbom/cyclonedx.json` existen y no contienen secretos.

## Referencias

- docs/security/threat-model.md STRIDE #5 Supply chain compromise, #6 PII leak, #7 Secret en repo/imagen
- config/checkstyle/suppressions.xml — no secrets en código
- .github/workflows/ci.yml — Trivy + SBOM + hadolint
