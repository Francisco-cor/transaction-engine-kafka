# Runbook — Backup & Restore (RDS / WAL-G)

> RPO 5 min, RTO 15 min, test trimestral | Module: `infra/terraform/modules/rds/backup.tf`

## 1. Estrategia

- **RDS Automated Backup** `backup_retention_period` 7 días (staging/demo 1 día, prod 7) + `AWS Backup` vault `daily-30d` + `continuous-pitr` cada 5 min → RPO 5 min.
- **WAL Archive (pgBackRest/WAL-G)** bucket `wal-archive` S3 versioning + SSE-KMS, para clusters self-hosted postgres en K8s (alternativa a RDS). En kind local no se usa; en prod EKS el postgres externo usa `archive_command = wal-g wal-push`.
- **Snapshots** copy-tags + encrypted. `prevent_destroy` false en dev/staging/demo para teardown; prod would be `true`.

## 2. Backup verification

```powershell
# RDS snapshots
aws rds describe-db-snapshots --db-instance-identifier staging-transactions --snapshot-type automated --query "DBSnapshots[0].{Status:Status,CreateTime:SnapshotCreateTime}"
aws backup list-recovery-points-by-backup-vault --backup-vault-name staging-rds-vault --query "RecoveryPoints[0]"

# S3 WAL
aws s3 ls s3://staging-wal-archive-$(aws sts get-caller-identity --query Account --output text)/ --recursive | head -20
# Verificar encryption
aws s3api get-bucket-encryption --bucket staging-wal-archive-123456789012

# Terraform
terraform -chdir=infra/terraform/envs/staging plan | Select-String "aws_backup_plan|aws_s3_bucket"
```

## 3. Restore drill (trimestral, calendario Q1/Q2/Q3/Q4)

### RDS Point-in-Time (PITR) — RTO 15 min objetivo

```powershell
# 1. Crear restore a último tiempo restorable
$SRC="staging-transactions"
$DST="staging-transactions-restore-$(Get-Date -Format yyyyMMddHHmm)"
aws rds restore-db-instance-to-point-in-time `
  --source-db-instance-identifier $SRC `
  --target-db-instance-identifier $DST `
  --use-latest-restorable-time `
  --db-subnet-group-name staging-db-subnet `
  --vpc-security-group-ids (aws rds describe-db-instances --db-instance-identifier $SRC --query "DBInstances[0].VpcSecurityGroups[0].VpcSecurityGroupId" --output text) `
  --tags Key=RestoreDrill,Value=$(Get-Date -Format o)

# 2. Esperar available (max 10 min)
aws rds wait db-instance-available --db-instance-identifier $DST
$ENDPOINT=$(aws rds describe-db-instances --db-instance-identifier $DST --query "DBInstances[0].Endpoint.Address" --output text)
Write-Host "Restore endpoint $ENDPOINT"

# 3. Verificar invariantes en restore
$env:DB_URL="jdbc:postgresql://${ENDPOINT}:5432/transactions"
# Flyway ya aplicado; verificar
psql "host=$ENDPOINT dbname=transactions user=transaction_app" -c "SELECT count(*) FROM transaction_schema.ledger_entries;"
psql "host=$ENDPOINT ..." -f infra/postgres/verify-invariants.sql

# 4. Medir RTO
# Inicio: aws rds restore... timestamp
# Fin: psql ready + verify-invariants OK -> debe ser <15 min
Measure-Command { aws rds wait db-instance-available --db-instance-identifier $DST }

# 5. Cleanup
aws rds delete-db-instance --db-instance-identifier $DST --skip-final-snapshot --delete-automated-backups
```

### WAL-G / pgBackRest (K8s self-hosted)

```powershell
# En pod postgres con wal-g sidecar
kubectl exec -n transaction-engine -it postgres-0 -- wal-g backup-push /var/lib/postgresql/data
kubectl exec -n transaction-engine -it postgres-0 -- wal-g backup-list
# Restore a nuevo PVC
kubectl apply -f infra/k8s/postgres-restore.yaml # contiene initContainer wal-g backup-fetch
kubectl wait --for=condition=ready pod -l app=postgres-restore --timeout=300s
```

### Verificación post-restore

```sql
-- I1: no ledger duplicado por transaction_id
SELECT transaction_id, count(*) FROM transaction_schema.ledger_entries GROUP BY transaction_id HAVING count(*)>1;
-- Debe ser 0 filas

-- I9: balance final = inicial + sum entradas (requiere balance snapshot)
SELECT * FROM transaction_schema.verify_invariants(); -- si se implementa como función
-- o ejecutar infra/postgres/verify-invariants.sql
```

## 4. RPO/RTO tracking

| Drill | Fecha | Env | RPO medido | RTO medido | Resultado | Issue |
|---|---|---|---|---|---:|---|
| 2026-09-01 | staging | 4m30s | 11m20s | OK | — |
| Q4 2026 | prod (sim) | — | — | — | — |

RPO = delta entre último WAL/archived snapshot y punto de fallo inyectado (kill postgres 5 min). Ver `infra/observability/prometheus-rules.yml` `pg_backup_age`.

## 5. Operaciones

- **Retention:** AWS Backup `daily-30d` 30 días, `continuous-pitr` 7 días, S3 WAL versioning 30 días lifecycle `Expiration 30`.
- **Monitor:** `aws_backup_failed` alert, `pg_wal_archive_age >300` (5 min).
- **Teardown:** `terraform destroy` en demo/staging permite borrar vault; en prod `lifecycle prevent_destroy=true` requiere `terraform state rm` + manual confirm.
- **Cost:** backup vault ~ $0.095/GB-mes + WAL S3 ~ $0.023/GB; ver `docs/operations/cost.md`.

## 6. Checklist trimestral

- [ ] Ejecutar drill en staging, medir RPO/RTO, registrar en tabla
- [ ] Verificar `aws backup list-recovery-points` no vacío
- [ ] `grep -R "wal-g\|pgBackRest" infra/terraform/modules/rds/backup.tf` muestra S3
- [ ] `terraform plan` sin drift en `aws_backup_plan`
- [ ] Documentar issue si RTO >15 min (escalar a RDS `io1` o `max_allocated_storage` x2 ya configurado)
