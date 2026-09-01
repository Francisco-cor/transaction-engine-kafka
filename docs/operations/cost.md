# Cost Limits & Teardown Policy

> Fase 10 | Infra: `infra/terraform/{modules,envs/{dev,staging,demo}}` | Backend: S3 + DynamoDB lock | Env protection: GitHub Environments

## 1. Principios

- **No secretos en repo:** `terraform.tfvars.example` sin passwords; valores reales via `TF_VAR_*`, Secrets Manager o `backend.hcl` local (gitignored).
- **Teardown explícito:** `terraform destroy` solo via `workflow_dispatch` con `environment` protection (staging/demo). Prod tiene `lifecycle { prevent_destroy = true }` comentado como sugerencia; activar al promover a prod real.
- **Cost guardrail:** PR con cambios `infra/terraform/**` muestra `infracost` comment; si delta >20% o mensual >$150 (dev) / $300 (staging) requiere aprobación `platform-team`.

## 2. Estimación mensual (us-east-1, Sep 2026)

| Recurso | dev (2x t3.medium EKS, t3.micro RDS, 2x t3.small MSK) | staging (3x t3.large, t3.small RDS, 3x m5.large MSK) | demo (2x t3.medium, t3.micro, 2x t3.small) |
|---|---|---|---|
| EKS control plane | $72 | $72 | $72 |
| EC2 nodes (EKS NG) | 2×$30 ≈ $60 | 3×$60 ≈ $180 | 2×$30 ≈ $60 |
| RDS postgres | $15 (t3.micro, 20GB) | $30 (t3.small, 50GB) | $15 |
| MSK | 2×$45 ≈ $90 | 3×$150 ≈ $450 | 2×$45 ≈ $90 |
| NAT GW + EIP | $35 + $5 | $35+5 | $35+5 |
| S3 WAL + Backup vault | $5 | $12 | $5 |
| Monitoring (helm prometheus) | $0 (in-cluster) o $20 CloudWatch | $20 | $0 |
| **Total estimado** | **~ $285/mes** | **~ $800/mes** | **~ $280/mes** |

> Fuente: `infracost breakdown --path infra/terraform/envs/dev` (requiere `INFRACOST_API_KEY` en CI). Ver `.github/workflows/deploy.yml` job `terraform-plan` que publica comment con costo.

### Optimización demostrada en F7

- `KAFKA_PARTITIONS=6` permite escalar a 6 pods sin crear nueva infra (solo HPA).
- `Hikari max 20` evita sobredimensionar RDS.
- `compression.type=zstd` reduce MSK storage/network ~30% (ver `docs/operations/capacity.md`).

## 3. Teardown policy

| Env | Destruible | Método | Aprobación |
|---|---|---|---|
| **dev** | Sí, sin ticket | `terraform -chdir=infra/terraform/envs/dev destroy` o `gh workflow run deploy.yml -f environment=dev -f action=destroy` | auto (environment `dev` sin protection) |
| **staging** | Sí, con aprobación | `workflow_dispatch` env `staging` action `destroy` → GitHub Environment protection `required_reviewers: 1` | platform-team |
| **demo** | Efímero, se recrea | `terraform destroy && terraform apply` documentado; bucket tfstate `demo` con `force_destroy=true` permitido | auto |
| **prod** *(futuro)* | No sin manual | Activar `lifecycle { prevent_destroy = true }` en `modules/rds/main.tf:45` y `modules/eks`, requiere `terraform state rm` + 2 aprobaciones + CAB | 2 reviewers + tag `v*` |

### Procedimiento destroy staging/demo

```powershell
# Local (requiere credenciales AWS con permisos)
terraform -chdir=infra/terraform/envs/staging init -backend-config=backend.hcl -reconfigure
terraform -chdir=infra/terraform/envs/staging plan -destroy
terraform -chdir=infra/terraform/envs/staging destroy -auto-approve

# Via GitHub (recomendado, auditado)
gh workflow run deploy.yml -f environment=staging -f action=destroy
gh run watch
gh run view --log

# Verificar
aws s3 ls s3://transaction-engine-tfstate-staging --recursive | head
aws dynamodb scan --table-name transaction-engine-tfstate-lock --query "Items[?LockID.S==\`staging/terraform.tfstate\`]"
helm list -n transaction-engine --kubeconfig <(aws eks update-kubeconfig --name transaction-engine-staging --dry-run)
```

### Re-creación

```powershell
terraform -chdir=infra/terraform/envs/staging init -backend-config=backend.hcl
terraform -chdir=infra/terraform/envs/staging apply
helm upgrade --install transaction-engine ./infra/helm/umbrella -f infra/helm/umbrella/values-staging.yaml -n transaction-engine --create-namespace --wait
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=transaction-engine -n transaction-engine --timeout=180s
powershell -File scripts/Invoke-Project.ps1 -Command verify-invariants # contra RDS staging endpoint
```

Tiempo estimado re-creación staging: ~12 min (VPC 2min, EKS 8min, RDS 5min paralelo con MSK).

## 4. Guardas en CI

- `terraform fmt -check -recursive` falla PR si no formateado.
- `terraform validate` en cada env.
- `infracost` comment en PR si `INFRACOST_API_KEY` presente; si coste > threshold, etiqueta `cost/review`.
- `helm lint` en `deploy.yml` `helm-deploy` job.
- `prevent_destroy` check: `grep -R prevent_destroy infra/terraform` debe ser `false` en dev/staging/demo; cambiar a `true` para prod requiere ADR.

## 5. Makefile

```makefile
tf-plan:
	terraform -chdir=infra/terraform/envs/dev init -backend-config=backend.hcl -reconfigure
	terraform -chdir=infra/terraform/envs/dev plan

tf-apply:
	terraform -chdir=infra/terraform/envs/dev apply

tf-destroy-dev:
	terraform -chdir=infra/terraform/envs/dev destroy

tf-fmt:
	terraform fmt -recursive infra/terraform
```

Ver `Makefile:14` targets `tf-plan/tf-apply/tf-fmt`.

## 6. Checklist teardown

- [ ] `terraform plan -destroy` revisado (no borra S3 tfstate bucket con `prevent_destroy` en bucket)
- [ ] `helm list -n transaction-engine` vacío o `helm uninstall transaction-engine -n transaction-engine`
- [ ] `aws s3 ls s3://transaction-engine-tfstate-*/` lock liberado (Dynamo `LockID` borrado tras `terraform force-unlock` si aplica)
- [ ] `grep -R "prevent_destroy.*true" infra/terraform/modules` solo en prod branch
- [ ] Coste mensual tras teardown = $0 (ver Cost Explorer `Project=transaction-engine` filter `Environment=staging`)
- [ ] `docs/runbooks/backup-restore.md` drill trimestral pasado antes de teardown (no perder último restore point)

## 7. Referencias

- `infra/terraform/modules/*/variables.tf:1` `required_version >=1.8`
- `infra/terraform/envs/{dev,staging,demo}/main.tf:1` backend S3 + modules
- `.github/workflows/deploy.yml:1` plan en PR, apply manual con environment
- `infra/k8s/external-secrets.yaml:1` evita secretos en tfvars
- `infra/terraform/modules/rds/backup.tf:1` backup vault afecta coste
