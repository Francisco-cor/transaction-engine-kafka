resource "aws_db_subnet_group" "this" {
  name       = "${var.env}-db-subnet"
  subnet_ids = var.private_subnets
  tags       = merge(var.tags, { Name = "${var.env}-db-subnet" })
}

resource "aws_security_group" "rds" {
  name_prefix = "${var.env}-rds-sg"
  vpc_id      = var.vpc_id
  tags        = var.tags
  ingress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]
  }
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.env}-pg16"
  family      = "postgres16"
  description = "Tuned for transaction-engine"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }
  tags = var.tags
}

resource "aws_db_instance" "this" {
  identifier             = "${var.env}-transactions"
  engine                 = "postgres"
  engine_version         = var.engine_version
  instance_class         = var.instance_class
  allocated_storage      = var.allocated_storage
  max_allocated_storage  = var.allocated_storage * 2
  storage_encrypted      = true
  db_name                = var.db_name
  username               = "transaction_app"
  manage_master_user_password = true
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  backup_retention_period = var.backup_retention_period
  backup_window          = "03:00-04:00"
  maintenance_window     = "Sun:04:00-Sun:05:00"
  deletion_protection    = var.deletion_protection
  copy_tags_to_snapshot  = true
  performance_insights_enabled = true
  tags = merge(var.tags, { Environment = var.env })

  lifecycle {
    prevent_destroy = false
  }
}

# Read replica for reporting (optional, disabled in dev)
resource "aws_db_instance" "replica" {
  count                  = var.env == "prod" ? 1 : 0
  identifier             = "${var.env}-transactions-replica"
  replicate_source_db    = aws_db_instance.this.identifier
  instance_class         = var.instance_class
  vpc_security_group_ids = [aws_security_group.rds.id]
  tags                   = var.tags
}
