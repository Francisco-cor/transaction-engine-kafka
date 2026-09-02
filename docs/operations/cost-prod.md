# Cost Prod y Guard — Fase 9

> Env `prod` hardened: `infra/terraform/envs/prod` multi-AZ 3AZ, RDS `db.r5.large 100GB multi_az 7d`, MSK `3 brokers m5.large`, S3 WAL cross-region, Vault prod, Infracost guard < $500.

## 1. Estimación prod

`infracost breakdown --path infra/terraform/envs/prod --format table` (requiere `INFRACOST_API_KEY`):

| Recurso | Config prod | Coste mensual us-east-1 |
|---|---|---|
| EKS control plane | 1 | $72 |
| EC2 nodes (EKS NG) | 3× m5.large | 3×$70 ≈ $210 |
| RDS postgres | `db.r5.large multi_az 100GB 7d` | $180 |
| MSK | 3× m5.large | 3×$150 ≈ $450 |
| NAT GW + EIP | 3 AZ NAT | $105 |
| S3 WAL + replica + Backup vault | versioning + replication us-west-2 | $15 |
| Monitoring (Prom+Loki+Tempo) | in-cluster | $0 + $20 CloudWatch logs |
| **Total prod** |  | **~ $1,050/mes** |

vs `dev $285`, `staging $800`, `demo $280` (`docs/operations/cost.md:11`). Delta prod vs staging ≈ +$250 por `multi_az` y `100GB`.

**Fuentes:** `aws pricing calculator` Sep 2026; MSK pricing `kafka.m5.large 0.21/h`; RDS `r5.large 0.24/h multi-az x2`.

## 2. Guard < $500

CI `terraform-plan` (`infracost/infracost-action@v0.10`) comenta coste en PR. Guard:

- Si `prod` delta `>$500` vs `main` base, workflow etiqueta `cost/review` y requiere `platform-team` approval (environment `prod` protection `required_reviewers: 2`).
- Si delta `>$200` y `<$500`, comment warning pero auto-merge permitido con 1 reviewer.
- Job `Cost guard <$500 diff` (`deploy.yml:74`) ejecuta `jq` sobre `infracost.json` y falla si `totalMonthlyCost >1500` (150% de baseline 1050).

```bash
# Local check
infracost breakdown --path infra/terraform/envs/prod --format json --out-file /tmp/infracost-prod.json
infracost diff --path infra/terraform/envs/prod --compare-to /tmp/infracost-base.json
jq '.diffTotalMonthlyCost' /tmp/infracost.json # debe ser <500
```

Si guard falla, reducir `rds_instance_class` a `db.t3.medium` o `msk broker_count 2` temporal, o mover `monitoring` a in-cluster sin CloudWatch.

## 3. Teardown prod (protegido)

Prod tiene `deletion_protection true` y `multi_az true` + `prevent_destroy false` pero GitHub Environment `prod` con `protection required_reviewers 2` y `wait_timer 30m` impide `destroy` accidental.

```powershell
# Solo con 2 aprobaciones + tag v1.0.0
gh workflow run deploy.yml -f environment=prod -f action=destroy
# En AWS console, desactivar deletion_protection manual antes de destroy:
aws rds modify-db-instance --db-instance-identifier prod-transactions --no-deletion-protection --apply-immediately
terraform -chdir=infra/terraform/envs/prod destroy -auto-approve
```

Coste tras teardown prod = $0; S3 `transaction-engine-tfstate-prod` y `wal-archive-replica` permanecen (coste $0.02/GB).

## 4. S3 replication coste

Cross-region replication `wal-archive → wal-archive-replica` en `us-west-2`:

- PUT replication $0.005/1000 requests, ~10k PUT/mes → $0.05
- Storage replica 50GB → $1.15/mes
- Inter-region transfer $0.02/GB → ~ $1/mes

Total ~$2.2/mes incluido en $15 S3.

## 5. Referencias

- `infra/terraform/envs/prod/main.tf:1` `allocated_storage 100`, `multi_az true`, `enable_cross_region_backup true`
- `infra/terraform/modules/rds/backup-cross.tf:1` replication role/policy
- `.github/workflows/deploy.yml:61` `infracost comment` + `Cost guard`
- `infra/k8s/external-secrets-vault-prod.yaml:1` prod secrets (coste Vault $0 in-cluster, prod Vault Enterprise $0.03/h si externo)
