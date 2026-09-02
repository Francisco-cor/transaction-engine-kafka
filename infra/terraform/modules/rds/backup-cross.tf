# Cross-region S3 replication for WAL archive + backup vault (F9 prod)
# Replicates wal_archive bucket to secondary region for RTO across region failure
# Enable via var.enable_cross_region_backup = true (default false for dev, true for prod)

variable "enable_cross_region_backup" {
  type    = bool
  default = false
}

variable "replica_region" {
  type    = string
  default = "us-west-2"
}

resource "aws_s3_bucket" "wal_archive_replica" {
  count  = var.enable_cross_region_backup && length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  bucket = "${var.env}-wal-archive-replica-${data.aws_caller_identity.current.account_id}"
  tags   = merge(var.tags, { Name = "${var.env}-wal-archive-replica", Replication = "true" })
}

resource "aws_iam_role" "replication" {
  count = var.enable_cross_region_backup && length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  name  = "${var.env}-s3-replication-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })
  tags = var.tags
}

resource "aws_iam_policy" "replication" {
  count = var.enable_cross_region_backup && length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  name  = "${var.env}-s3-replication-policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
        Resource = [aws_s3_bucket.wal_archive[0].arn]
      },
      {
        Effect = "Allow"
        Action = ["s3:GetObjectVersion*", "s3:GetObject*", "s3:List*"]
        Resource = ["${aws_s3_bucket.wal_archive[0].arn}/*"]
      },
      {
        Effect = "Allow"
        Action = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags", "s3:ObjectOwnerOverrideToBucketOwner"]
        Resource = ["${aws_s3_bucket.wal_archive_replica[0].arn}/*"]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "replication" {
  count      = var.enable_cross_region_backup && length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  role       = aws_iam_role.replication[0].name
  policy_arn = aws_iam_policy.replication[0].arn
}

resource "aws_s3_bucket_versioning" "wal_archive_replica" {
  count  = var.enable_cross_region_backup && length(aws_s3_bucket.wal_archive_replica) > 0 ? 1 : 0
  bucket = aws_s3_bucket.wal_archive_replica[0].id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_replication_configuration" "wal_archive" {
  count  = var.enable_cross_region_backup && length(aws_s3_bucket.wal_archive) > 0 ? 1 : 0
  role   = aws_iam_role.replication[0].arn
  bucket = aws_s3_bucket.wal_archive[0].id
  rule {
    id     = "wal-cross-region"
    status = "Enabled"
    filter {}
    destination {
      bucket        = aws_s3_bucket.wal_archive_replica[0].arn
      storage_class = "STANDARD"
    }
    delete_marker_replication { status = "Enabled" }
  }
  depends_on = [aws_s3_bucket_versioning.wal_archive, aws_s3_bucket_versioning.wal_archive_replica]
}

# Restore drill cross-region — document in backup-restore.md Q drill
# aws rds restore-db-instance-to-point-in-time --source-db-instance-identifier prod-transactions --target-db-instance-identifier prod-transactions-dr-$(date +%Y%m%d) --use-latest-restorable-time --region us-west-2
