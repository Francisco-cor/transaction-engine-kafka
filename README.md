# Financial Transaction & Reconciliation Platform

Base incremental para una plataforma de movimientos financieros con PostgreSQL, Kafka y procesamiento asíncrono. Este repositorio cubre las fases 0 a 4: fundación, infraestructura local, ingesta/outbox y ledger idempotente.

## Requisitos

- Java 21
- Maven 3.9+
- Docker Desktop con Docker Compose v2
- PowerShell 7 recomendado para los comandos auxiliares

## Inicio rápido

Desde la raíz del repositorio:

```powershell
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command build
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command up
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command smoke
```

También se puede usar `make build`, `make up`, `make smoke`, `make down` y `make logs` en entornos con Make disponible.

La aplicación placeholder se ejecuta fuera de Compose con:

```powershell
mvn -pl services/transaction-service spring-boot:run
```

Endpoint disponible: `GET http://localhost:8080/api/v1/placeholder`.

## Fase 2: API y outbox

Con la infraestructura levantada, inicia el servicio desde la raíz:

```powershell
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command up
mvn -pl services/transaction-service spring-boot:run
```

Crear una transacción requiere `Idempotency-Key`; `X-Tenant-Id` identifica el scope de idempotencia y por defecto vale `demo` para desarrollo:

```powershell
$body = '{"accountId":"demo-acc-001","amount":10.00,"type":"DEBIT","currency":"MXN"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/transactions `
  -Headers @{ 'Idempotency-Key' = 'demo-key-001'; 'X-Tenant-Id' = 'demo' } `
  -ContentType 'application/json' -Body $body
```

El endpoint devuelve `202 Accepted`, persiste `transactions` y `outbox_events` en una única transacción y el publisher publica `transactions.created.v1` con key `accountId`. Repetir la misma key y body devuelve el mismo `transactionId`; cambiar el body devuelve `409 Conflict`. La consulta es `GET http://localhost:8080/transactions/{transactionId}`.

## Servicios locales

| Servicio | Puerto | Uso |
|---|---:|---|
| Kafka | 9092 | Broker accesible desde el host |
| PostgreSQL | 5432 (o `$env:POSTGRES_HOST_PORT=5433`) | Base `transactions` |
| Redis | 6379 | Cache/estado auxiliar |
| Schema Registry | 8081 | Contratos Kafka |
| Jaeger | 16686 | UI de trazas; OTLP en 4317/4318 |
| Prometheus | 9090 | Métricas |
| Grafana | 3000 | Dashboards locales |
| transaction-service | 8080 | API de ingesta |
| ledger-service | 8082 | Consumer y health/readiness |
| fraud-service | 8083 | Reglas deterministas, decisiones idempotentes y outbox |
| reconciliation-service | 8084 | Worker, clasificación y replay controlado |

Credenciales de desarrollo por defecto: PostgreSQL admin `postgres/postgres_dev`, aplicación `transaction_app/transaction_app_dev`, migraciones `transaction_migrator/transaction_migrator_dev`, Grafana `admin/admin_dev`. Son valores exclusivos para el entorno local y se pueden cambiar en `infra/docker-compose/.env`.

## Fase 4: fraude y reconciliación

El flujo local consume `transactions.created.v1` en grupos independientes. `fraud-service` persiste una decisión única por `transactionId`, publica `transactions.fraud-decisions.v1` y usa Redis únicamente como cache auxiliar con fallback a PostgreSQL. `reconciliation-service` compara la transacción, el evento creado, el ledger, la decisión de fraude y el evento de resultado; guarda `MATCHED`, `MISSING`, `DUPLICATE`, `MISMATCH` o `PENDING`.

```powershell
# Consultar el resultado de reconciliación
Invoke-RestMethod http://localhost:8084/reconciliation/{transactionId}

# Solicitar replay administrativo de un caso pendiente
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8084/reconciliation/{transactionId}/replay `
  -Headers @{ 'X-Replay-Reason' = 'manual-verification' }
```

Si el puerto 5432 ya está ocupado por otro PostgreSQL local, levanta Compose con `$env:POSTGRES_HOST_PORT='5433'`; el valor por defecto continúa siendo 5432.

## Comandos

```powershell
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command test
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command integration-test
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command quality
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command scan
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command logs
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command inspect
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command down
powershell -NoProfile -File .\scripts\Invoke-Project.ps1 -Command clean-data -RemoveData
```

`clean-data` requiere el switch explícito `-RemoveData` y elimina únicamente los volúmenes nombrados de este Compose. `load` y `chaos` están reservados para las fases posteriores y fallan de forma explícita mientras no exista su implementación.

## Estructura

- `libs/event-contracts`: tipos pequeños y contratos versionados.
- `services/transaction-service`: API de ingesta, idempotencia y transactional outbox.
- `services/ledger-service`: consumer idempotente, locking de cuenta y outbox de resultados.
- `services/fraud-service`: reglas deterministas, Redis auxiliar y decisiones idempotentes.
- `services/reconciliation-service`: worker de consistencia y replay controlado.
- `infra/docker-compose`: Kafka KRaft, PostgreSQL, Redis, Schema Registry y observabilidad.
- `infra/postgres/migrations`: migraciones Flyway iniciales.
- `docs/adr`: decisiones arquitectónicas.
- `docs/contracts`: fixtures y schemas de eventos.
- `docs/operations`: SLOs, límites y retención de datos de demo.

El roadmap de implementación se mantiene localmente en `IMPLEMENTATION_PLAN.md` y se excluye del repositorio público.
