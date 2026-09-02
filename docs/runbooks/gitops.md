# Runbook — GitOps con ArgoCD (F8)

> Chart: `infra/helm/umbrella` → ArgoCD `ApplicationSet` dev/staging/demo + NetworkPolicy + Linkerd mTLS + HPA custom kafka lag + KEDA ScaledJob

## 1. Arquitectura

```
GitHub main
  └─> ArgoCD ApplicationSet (infra/argocd/applicationset.yaml)
        ├─ dev (values-dev.yaml, autoSync true, namespace transaction-engine-dev)
        ├─ staging (values.yaml, autoSync false, transaction-engine-staging)
        └─ demo (values.yaml, autoSync false, transaction-engine-demo)
             └─> Helm umbrella 0.5.0 (6 deployments + Job migrate + PDB/HPA/KEDA)
                      └─> ServiceAccount least-privilege (automount false)
                      └─> Linkerd mTLS (inject enabled)
                      └─> NetworkPolicy deny-all + allowlist
```

**Sync:** ArgoCD `automated prune selfHeal` + `retry 5 backoff 5s*2 max 3m` + `CreateNamespace=true`. Dev auto-sync en 60s tras push a `main`; staging/demo sync manual vía UI/CLI `argocd app sync transaction-engine-staging`.

## 2. Instalación

```powershell
# 1. ArgoCD en cluster (kind/EKS)
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/v2.10.5/manifests/install.yaml
# o helm:
helm repo add argo https://argoproj.github.io/argo-helm
helm upgrade --install argocd argo/argo-cd -n argocd -f infra/k8s/argocd-values.yaml --create-namespace

# 2. Esperar
kubectl wait --for=condition=available deployment/argocd-server -n argocd --timeout=180s

# 3. Aplicar ApplicationSet
kubectl apply -f infra/argocd/applicationset.yaml -n argocd
kubectl apply -f infra/k8s/argocd-install.yaml -n argocd # bootstrap placeholder

# 4. Verificar
kubectl get applicationset -n argocd
kubectl get application -n argocd -l app.kubernetes.io/part-of=transaction-engine
argocd app list # si CLI instalado
```

**Kind local:** `infra/kind/kind-config.yaml` + `scripts/kind-up.ps1` crea cluster `transaction-engine` con 2 workers; luego instalar ArgoCD igual.

## 3. NetworkPolicy

`infra/helm/umbrella/templates/networkpolicy.yaml` genera 4 políticas cuando `networkPolicy.enabled=true` (default):

- `deny-all` `podSelector: {}` `policyTypes [Ingress,Egress]` `ingress []` `egress DNS 53` — bloquea todo por defecto.
- `allow-ledger-to-postgres` `podSelector postgres` `ingress from ledger/transaction/fraud` `port 5432`
- `allow-gateway-to-services` `podSelector transaction-service` `from gateway` `port 8080`
- `allow-kafka-clients` `podSelector kafka` `from any umbrella` `ports 29092/9092`

**Verificación:**

```powershell
kubectl get networkpolicy -n transaction-engine
kubectl describe networkpolicy transaction-engine-deny-all -n transaction-engine
kubectl exec -n transaction-engine deploy/transaction-engine-ledger-service -- curl -v postgres:5432 # debe conectar
kubectl exec -n transaction-engine deploy/transaction-engine-fraud-service -- curl -v transaction-service:8080 # bloqueado si no allowlist
```

Debug con `kubectl run tmp --image=alpine -n transaction-engine -- curl`.

## 4. ServiceAccount least-privilege

`infra/helm/umbrella/templates/serviceaccounts.yaml` genera 6 SA:

- `transaction-engine-transaction-service` … `api-gateway` + `migrate` (`serviceaccount-migrate.yaml`)
- `automountServiceAccountToken: false` en todas
- `securityContext runAsUser 65532 runAsNonRoot readOnlyRootFilesystem` en pods

**Verificación:**

```powershell
kubectl get sa -n transaction-engine
kubectl get pod -n transaction-engine -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.spec.serviceAccountName}{" "}{.spec.automountServiceAccountToken}{"\n"}{end}'
kubectl auth can-i --list --as=system:serviceaccount:transaction-engine:transaction-engine-ledger-service -n transaction-engine
```

## 5. Linkerd mTLS

`infra/helm/umbrella/values.yaml:126` `linkerd.enabled true inject enabled` + `serviceaccounts.yaml` y `deployment-*.yaml` inyectan `linkerd.io/inject: enabled` en SA y pod annotations.

**Instalación Linkerd (kind):**

```powershell
curl --proto '=https' --tlsv1.2 -sSfL https://run.linkerd.io/install | sh
linkerd install --crds | kubectl apply -f -
linkerd install | kubectl apply -f -
linkerd check

# Verificar inyección
kubectl get deployment -n transaction-engine -o yaml | Select-String "linkerd.io/inject"
kubectl get pod -n transaction-engine -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.metadata.annotations.linkerd\.io\/inject}{"\n"}{end}'
linkerd -n transaction-engine check --proxy
linkerd viz stat deployment -n transaction-engine
```

mTLS automático entre `ledger-service ↔ postgres` y `gateway ↔ transaction-service` sin cambios de código; métricas `linkerd viz edges`.

## 6. HPA custom + KEDA

**HPA CPU (existing):** `hpa.yaml` `cpu 70% memory 80% min1 max6` + `behavior stabilization 60s`.

**HPA kafka lag custom:** `hpa-custom.yaml` `ledger-kafka-lag` `external metric kafka_consumer_group_lag topic transactions.created.v1 consumergroup ledger-service target 100`. Requiere `prometheus-adapter` con regla:

```yaml
rules:
- seriesQuery: 'kafka_consumer_group_lag{topic="transactions.created.v1"}'
  resources: {overrides: {consumergroup: {resource: deployment}}}
  name: {matches: "kafka_consumer_group_lag", as: "kafka_consumer_group_lag"}
```

**KEDA ScaledObject (existing):** `scaledobject-ledger.yaml` `kafka lagThreshold 100` `pollingInterval 15`.

**KEDA ScaledJob:** `hpa-custom.yaml` `reconciliation-replay` `maxReplicaCount 5` `topic transactions.created.v1.DLT lag 10` para jobs de replay.

**Verificación:**

```powershell
kubectl get hpa -n transaction-engine
kubectl describe hpa transaction-engine-ledger-kafka-lag -n transaction-engine
kubectl get scaledobject,scaledjob -n transaction-engine
kubectl describe scaledobject transaction-engine-ledger-service -n transaction-engine

# Carga para triggear lag
k6 run load-tests/k6-20k.js
kubectl get pods -n transaction-engine -w
kubectl top pods -n transaction-engine
```

Si `prometheus-adapter` no instalado, custom HPA queda `Unknown` — fallback a KEDA.

## 7. PDB y topologySpread

`pdb.yaml` genera 6 `PodDisruptionBudget maxUnavailable 1` (uno por componente). `deployment-*.yaml` tienen `terminationGracePeriodSeconds 45` `preStop sleep 10` `podAntiAffinity preferredDuringScheduling weight 100` `topologySpreadConstraints maxSkew 1`.

**Verificación:**

```powershell
kubectl get pdb -n transaction-engine
kubectl drain kind-worker --ignore-daemonsets --delete-emptydir-data --dry-run=client
kubectl get pods -n transaction-engine -o wide | Select-String "NODE"
```

## 8. Operaciones GitOps

- **Rollback:** `argocd app set transaction-engine-dev --revision <sha>` + `argocd app sync` o `helm rollback transaction-engine -n transaction-engine-dev`.
- **Diff:** `argocd app diff transaction-engine-dev` o `helm diff upgrade`.
- **Secrets:** prod usa `ExternalSecret vault-backend` (`infra/k8s/external-secrets-vault.yaml`) — no plain en `values.yaml`. ArgoCD sync falla si secretStore no existe; instalar ESO antes.
- **Sync failure:** `kubectl describe application transaction-engine-dev -n argocd` → `status.sync.error` + `kubectl logs deploy/argocd-application-controller -n argocd`.

## 9. Checklist F8

- [ ] `helm lint infra/helm/umbrella -f values.yaml -f values-dev.yaml` verde
- [ ] `kubectl apply --dry-run=client -f infra/helm/umbrella/templates/serviceaccounts.yaml` sin error
- [ ] `kubectl get networkpolicy -n transaction-engine` 4 políticas
- [ ] `kubectl get sa -n transaction-engine` 7 SA `automount false`
- [ ] `argocd app list` `Synced/Healthy` para dev
- [ ] `kubectl get hpa,scaledobject,scaledjob -n transaction-engine` con lag 100
- [ ] `linkerd check --proxy -n transaction-engine` verde
- [ ] `kubectl rollout restart deploy/transaction-engine-ledger-service -n transaction-engine` sin loss + `verify-invariants` ok

## 10. Referencias

- `infra/helm/umbrella/templates/serviceaccounts.yaml:1`, `networkpolicy.yaml:1`, `hpa.yaml:1`, `hpa-custom.yaml:1`, `pdb.yaml:1`, `scaledobject-ledger.yaml:1`
- `infra/helm/umbrella/values.yaml:126` `linkerd`
- `infra/argocd/applicationset.yaml:1`, `infra/k8s/argocd-install.yaml:1`
- `docs/adr/ADR-0xx` (próximo ADR GitOps)
