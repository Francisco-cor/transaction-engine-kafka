resource "aws_security_group" "msk" {
  name_prefix = "${var.env}-msk-sg"
  vpc_id      = var.vpc_id
  tags        = var.tags
  ingress {
    from_port   = 9092
    to_port     = 9098
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/16"]
  }
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.env}-msk"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnets
    security_groups = [aws_security_group.msk.id]
    storage_info {
      ebs_storage_info {
        volume_size = var.ebs_volume_size
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT"
      in_cluster    = true
    }
    encryption_at_rest_kms_key_arn = null
  }

  client_authentication {
    sasl { iam = true }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  tags = merge(var.tags, { Environment = var.env })
}

resource "aws_msk_configuration" "this" {
  kafka_versions = [var.kafka_version]
  name           = "${var.env}-msk-config"
  server_properties = <<PROPERTIES
auto.create.topics.enable=false
default.replication.factor=3
min.insync.replicas=2
num.partitions=6
compression.type=zstd
log.retention.hours=168
PROPERTIES
}
