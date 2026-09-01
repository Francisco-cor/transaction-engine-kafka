# Secrets Handling

## Local

- `infra/docker-compose/.env` is gitignored (`.gitignore:11`). Copy from `.env.example` and never commit.
- `infra/docker-compose/secrets/` is also gitignored; alternative is Docker secrets files per service:
  ```
  POSTGRES_APP_PASSWORD_FILE=/run/secrets/postgres_app_password
  ```
  Compose can read via `env_file` + `secrets`.

## Images and Manifests

- No secret in `Dockerfile`, `application.yml`, `deployment.yaml`, `helm values`.
- `helm values` use `external-secrets` or `sealed-secrets`.
- CI uses `secrets` from GitHub Environments, not `env`.

## Logs

- `logback-spring.xml` via `libs/observability` includes MDC but never logs `Authorization`, `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT`.
- `TransactionExceptionHandler` masks messages: only returns safe error without stack.
- Search for leaks: `grep -R -i "password\|secret\|authorization" logs/ || true` must be 0.

## Rotation

- Dev secrets in `.env.example` end with `_dev` and are not production.
- For prod: `transaction_app` / `transaction_migrator` / `redis_dev` / `grafana admin` must be overridden via env.

## Checklist Fase 3

- [ ] `docker inspect` on any container shows no `-e DB_PASSWORD` plain? Actually env visible via inspect; use Docker secrets in Swarm/K8s.
- [ ] `helm template` shows no `password: ..._dev`
- [ ] `grep -i password` en `reports/` 0 hits
