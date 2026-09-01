# Runbook — Kubernetes (kind) y Helm Umbrella

> Fase 9 | Chart: `infra/helm/umbrella` | Values: `values.yaml` / `values-dev.yaml` | Kind: `infra/kind/kind-config.yaml`

## 1. Quickstart local kind

```powershell
# Requisitos: Docker Desktop, kind, kubectl, helm en PATH
powershell -File scripts/kind-up.ps1
# o via wrapper
powershell -File scripts/Invoke-Project.ps1 -Command k8s-up

# Verificación
kubectl get pods -n transaction-engine
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=transaction-engine -n transaction-engine --timeout=180s
kubectl get pdb -n transaction-engine
kubectl get hpa -n transaction-engine
helm list -n transaction-engine

# Smoke via kubectl
powershell -File scripts/Invoke-Project.ps1 -Command k8s-smoke
# o manual
kubectl port-forward svc/transaction-engine-transaction-service 8080:8080 -n transaction-engine &
curl http://localhost:8080/actuator/health/readiness
kubectl port-forward svc/transaction-engine-api-gateway 8085:8085 -n transaction-engine &
curl http://localhost:8085/actuator/health/readiness

# Logs
powershell -File scripts/Invoke-Project.ps1 -Command k8s-logs
kubectl logs -l app.kubernetes.io/component=ledger-service -n transaction-engine --tail=100

# Teardown
powershell -File scripts/kind-down.ps1
powershell -File scripts/Invoke-Project.ps1 -Command k8s-down
```

## 2. Arquitectura Helm

```
infra/helm/umbrella/
  Chart.yaml
  values.yaml (prod-safe, no plain secrets)
  values-dev.yaml ( _dev secrets only for kind)
  templates/
    _helpers.tpl
    configmap.yaml (DB_URL, KAFKA_BOOTSTRAP_SERVERS, OTEL_ENDPOINT)
    secret.yaml (dev-only)
    deployment-*.yaml x6 (transaction, ledger, fraud, reconciliation, notification, gateway)
    service-*.yaml x6 ClusterIP
    job-migrate.yaml (Flyway pre-install hook)
    serviceaccount-migrate.yaml
    pdb.yaml (6x maxUnavailable 1)
    hpa.yaml (6x CPU 70% memory 80% min1 max6)
    scaledobject-ledger.yaml (KEDA kafka lag 100)
```

Secrets en prod: No usar `secret.yaml`. Crear `ExternalSecret`:

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata: {name: transaction-engine-external-secrets, namespace: transaction-engine}
spec:
  secretStoreRef: {name: vault-backend, kind: ClusterSecretStore}
  target: {name: transaction-engine-external-secrets}
  data:
    - {secretKey: DB_PASSWORD, remoteRef: {key: prod/transaction_engine, property: db_password}}
    - {secretKey: REDIS_PASSWORD, remoteRef: {key: prod/transaction_engine, property: redis_password}}
```

Helm values prod:

```powershell
helm upgrade --install transaction-engine ./infra/helm/umbrella `
  -f infra/helm/umbrella/values.yaml `
  --set global.environment=prod `
  --set keda.enabled=true `
  --namespace transaction-engine --create-namespace --wait
```

## 3. Probes y resources

Cada Deployment define (values.yaml):

```yaml
probes:
  liveness: {path: /actuator/health/liveness, initialDelaySeconds: 60, period 10}
  readiness: {path: /actuator/health/readiness, initialDelaySeconds: 30, period 5}
resources:
  requests: {cpu: 250m, memory: 256Mi}
  limits: {cpu: 500m, memory: 512Mi}
securityContext:
  runAsNonRoot: true, runAsUser: 65532, fsGroup: 65532, seccompProfile: RuntimeDefault
containerSecurityContext:
  readOnlyRootFilesystem: true, allowPrivilegeEscalation: false, capabilities: {drop: [ALL]}
```

Verificación:

```powershell
kubectl describe pod -l app.kubernetes.io/component=ledger-service -n transaction-engine | Select-String -Pattern "Liveness|Readiness|Limits|Requests"
kubectl get pod -n transaction-engine -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].ready}{"\n"}{end}'
```

## 4. Rolling update sin pérdida

Garantías:

- `terminationGracePeriodSeconds: 45` + `preStop sleep 10` + `server.shutdown=graceful` + `lifecycle.timeout-per-shutdown-phase=30s` (`application.yml:2`)
- `strategy RollingUpdate maxUnavailable 0 maxSurge 1`
- `PDB maxUnavailable 1` impide eviction masiva
- `ACK MANUAL_IMMEDIATE` solo post-commit evita pérdida de offsets
- Outbox lease/claim evita duplicación durante rollout

Prueba:

```powershell
# Terminal 1: generar carga 10 rps
powershell -File scripts/Invoke-Project.ps1 -Command load
# o k6 run load-tests/k6-transactions.js --vus 5 --duration 60s

# Terminal 2: rollout
kubectl rollout restart deployment/transaction-engine-ledger-service -n transaction-engine
kubectl rollout status deployment/transaction-engine-ledger-service -n transaction-engine --timeout=120s
# Verificar que no hubo missing/duplicados
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants
kubectl logs -l app.kubernetes.io/component=ledger-service -n transaction-engine | Select-String "duplicate|DLT"
```

## 5. HPA y KEDA

```powershell
kubectl get hpa -n transaction-engine
kubectl describe hpa transaction-engine-ledger-service -n transaction-engine
kubectl top pods -n transaction-engine  # requiere metrics-server en kind: kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# KEDA (opcional)
kubectl apply -f https://github.com/kedacore/keda/releases/download/v2.14.0/keda-2.14.0.yaml
kubectl get scaledobject -n transaction-engine
kubectl describe scaledobject transaction-engine-ledger-service -n transaction-engine
# Forzar lag: enviar 1000 tx con mismo accountId (hot) y observar escala
k6 run load-tests/k6-transactions.js
kubectl get pods -n transaction-engine -w
```

Umbral: `lagThreshold=100` para `transactions.created.v1` grupo `ledger-service`. Si `kubectl get --raw /apis/keda.sh/v1alpha1/namespaces/transaction-engine/scaledobjects` muestra `isActive=true`, HPA escalará a 3-6 pods.

## 6. Flyway Job

```powershell
kubectl get jobs -n transaction-engine
kubectl logs job/transaction-engine-migrate -n transaction-engine
kubectl get configmap transaction-engine-migrations -n transaction-engine -o yaml
# Re-ejecutar manual
kubectl delete job transaction-engine-migrate -n transaction-engine
helm upgrade --install transaction-engine ./infra/helm/umbrella -f infra/helm/umbrella/values-dev.yaml -n transaction-engine --wait
```

Si falla con `connectRetries`: verificar `DB_URL` en `configmap.yaml` y `secret` para migrator.

## 7. Troubleshooting

### Pods Pending

```powershell
kubectl describe pod -l app.kubernetes.io/component=transaction-service -n transaction-engine | Select-String "Events|FailedScheduling"
kubectl top nodes
# Causa típica: recursos requests 256Mi/250m *6 = 1.5Gi + infra; kind workers con 2-4Gi pueden saturar. Ajustar replicaCount 1 o requests en values-dev.yaml.
```

### CrashLoopBackOff

```powershell
kubectl logs -l app.kubernetes.io/component=api-gateway -n transaction-engine --previous
kubectl describe pod -n transaction-engine | Select-String "Liveness|Readiness|Back-off"
# Causa: DB/Kafka no reachable desde K8s. Ver configmap KAFKA_BOOTSTRAP_SERVERS y DB_URL. En kind, kafka/postgres deben ser in-cluster o hostPort.
```

### Job migrate Failed

```powershell
kubectl logs job/transaction-engine-migrate -n transaction-engine
# Check: FLYWAY_URL=jdbc:postgresql://postgres:5432/transactions pero postgres no existe en namespace. Solución: desplegar postgres via helm o usar external DB via values.
helm template transaction-engine ./infra/helm/umbrella --debug | Select-String "FLYWAY"
```

### PDB bloquea drain

```powershell
kubectl get pdb -n transaction-engine
kubectl drain kind-worker --ignore-daemonsets --delete-emptydir-data
# Si PDB maxUnavailable 1 y replicas 1, drain bloqueado. Escalar a 2 o borrar PDB temporal.
```

### HPA no escala

```powershell
kubectl describe hpa -n transaction-engine
kubectl get --raw /apis/metrics.k8s.io/v1beta1/namespaces/transaction-engine/pods
# Instalar metrics-server en kind si no existe
```

### KEDA no escala

```powershell
kubectl logs -n keda deployment/keda-operator | Select-String "ledger-service"
kubectl get scaledobject -n transaction-engine -o yaml | Select-String "lagThreshold|bootstrapServers"
# Verificar topic existe y consumerGroup lag real: kubectl exec -it kafka -- kafka-consumer-groups --bootstrap-server kafka:29092 --describe --group ledger-service
```

### ImagePullBackOff

```powershell
kubectl describe pod -n transaction-engine | Select-String "Failed to pull|ImageInspectError"
# En kind, imagen debe ser cargada: kind load docker-image transaction-service:dev --name transaction-engine
# O usar imagePullPolicy Never para dev
```

### Anti-affinity no distribuye

```powershell
kubectl get pods -n transaction-engine -o wide | Select-String "NODE"
# preferredDuringScheduling no garantiza distribución en 2 nodos con 6 pods; cambiar a requiredDuringScheduling para test o añadir 3er worker en kind-config.yaml
```

## 8. Operaciones

- **Scale manual:** `kubectl scale deployment transaction-engine-ledger-service --replicas=3 -n transaction-engine`
- **Logs agregados:** `kubectl logs -l app.kubernetes.io/instance=transaction-engine -n transaction-engine --all-containers --tail=50 | Select-String "transaction_id"`
- **Port-forward todo:** `kubectl port-forward svc/transaction-engine-grafana 3000:3000 -n transaction-engine`
- **Helm diff:** `helm diff upgrade transaction-engine ./infra/helm/umbrella -f values-dev.yaml -n transaction-engine` (requiere helm-diff plugin)
- **Upgrade con nueva imagen:** `docker build -t transaction-service:dev2 . ; kind load docker-image transaction-service:dev2 --name transaction-engine ; helm upgrade transaction-engine ./infra/helm/umbrella --set images.transactionService=transaction-service:dev2 -n transaction-engine`

## 9. Checklist rollout

- [ ] `helm lint` verde
- [ ] `kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=transaction-engine --timeout=180s`
- [ ] `kubectl rollout status deployment/* --timeout=60s` sin error
- [ ] `kubectl get pdb -n transaction-engine` 6 PDBs
- [ ] `kubectl top pods` sin OOMKilled
- [ ] `verify-invariants` SQL sin violaciones tras rollout
- [ ] `k6` 10k con hot keys p95<500ms recuperación <14s (F7)

## 10. Referencias

- `infra/helm/umbrella/Chart.yaml:1`, `values.yaml:1`, `values-dev.yaml:1`
- `infra/helm/umbrella/templates/deployment-*.yaml:1` probes/resources/securityContext/terminationGrace/anti-affinity
- `infra/helm/umbrella/templates/job-migrate.yaml:1`, `infra/kind/kind-config.yaml:1`
- `scripts/kind-up.ps1:1`, `scripts/Invoke-Project.ps1:1` k8s-up/k8s-smoke
- `docs/security/threat-model.md` (K8s probes, PDB, image pinning)
