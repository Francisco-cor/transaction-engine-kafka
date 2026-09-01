# Backup & restore — WAL-G / pgBackRest style via AWS Backup + S3 WAL archive
# RPO 5min (WAL every 5min or continuous), RTO 15min (point-in-time restore drill)

variable "backup_bucket_name" {
  description = "S3 bucket for WAL-G / pgBackRest archive (optional override)"
  type        = string
  default     = null
}

variable "enable_aws_backup" {
  description = "Enable AWS Backup plan for RDS"
  type        = bool
  default     = true
}

resource "aws_s3_bucket" "wal_archive" {
  count  = var.backup_bucket_name != null ? 1 : 0
  bucket = var.backup_bucket_name != null ? var.backup_bucket_name : "${var.env}-wal-archive-${data.aws_caller_identity.current.account_id}"
  tags   = merge(var.tags, { Name = "${var.env}-wal-archive" })
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket_versioning" "wal_archive" {
  count  = length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  bucket = aws_s3_bucket.wal_archive[0].id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "wal_archive" {
  count  = length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  bucket = aws_s3_bucket.wal_archive[0].id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "aws:kms" }
  }
}

resource "aws_backup_vault" "rds" {
  count = var.enable_aws_backup ? 1 : 0
  name  = "${var.env}-rds-vault"
  tags  = var.tags
}

resource "aws_backup_plan" "rds" {
  count = var.enable_aws_backup ? 1 : 0
  name  = "${var.env}-rds-backup-plan"
  rule {
    rule_name         = "daily-30d"
    target_vault_name = aws_backup_vault.rds[0].name
    schedule          = "cron(0 5 * * ? *)"
    lifecycle { delete_after = 30 }
    recovery_point_tags = var.tags
  }
  rule {
    rule_name         = "continuous-pitr"
    target_vault_name = aws_backup_vault.rds[0].name
    schedule          = "cron(0/5 * * * ? *)"
    lifecycle { delete_after = 7 }
  }
  advanced_backup_setting {
    backup_options = { WindowsVSS = "disabled" }
    resource_type  = "RDS"
  }
  tags = var.tags
}

resource "aws_backup_selection" "rds" {
  count        = var.enable_aws_backup ? 1 : 0
  name         = "${var.env}-rds-selection"
  iam_role_arn = aws_iam_role.backup[0].arn
  plan_id      = aws_backup_plan.rds[0].id
  resources    = [aws_db_instance.this.arn]
}

resource "aws_iam_role" "backup" {
  count = var.enable_aws_backup ? 1 : 0
  name  = "${var.env}-backup-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "backup.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "backup" {
  count      = var.enable_aws_backup ? 1 : 0
  role       = aws_iam_role.backup[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

# Point-in-time restore drill — manual step documented in docs/runbooks/backup-restore.md
# Example: aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier staging-transactions --target-db-instance-identifier staging-transactions-restore-$(date +%Y%m%d) --use-latest-restorable-time --db-subnet-group-name staging-db-subnet
