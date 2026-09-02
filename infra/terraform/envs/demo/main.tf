terraform {
  required_version = ">= 1.8.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 2.10"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.20"
    }
  }
  backend "s3" {}
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Environment = "demo"
      Project     = "transaction-engine"
      ManagedBy   = "terraform"
    }
  }
}

locals {
  env          = "demo"
  cluster_name = "transaction-engine-demo"
  common_tags  = { Environment = local.env, Project = "transaction-engine", Demo = "true" }
}

module "vpc" {
  source          = "../../modules/vpc"
  env             = local.env
  vpc_cidr        = var.vpc_cidr
  azs             = var.azs
  private_subnets = var.private_subnets
  public_subnets  = var.public_subnets
  tags            = local.common_tags
}

module "eks" {
  source             = "../../modules/eks"
  env                = local.env
  cluster_name       = local.cluster_name
  kubernetes_version = var.kubernetes_version
  vpc_id             = module.vpc.vpc_id
  private_subnets    = module.vpc.private_subnets
  public_subnets     = module.vpc.public_subnets
  node_instance_types = var.node_instance_types
  desired_size       = var.desired_size
  min_size           = var.min_size
  max_size           = var.max_size
  tags               = local.common_tags
}

module "rds" {
  source           = "../../modules/rds"
  env              = local.env
  vpc_id           = module.vpc.vpc_id
  private_subnets  = module.vpc.private_subnets
  db_name          = var.db_name
  instance_class   = "db.t3.micro"
  allocated_storage = 20
  backup_retention_period = 7
  deletion_protection = false
  tags             = local.common_tags
}

module "msk" {
  source            = "../../modules/msk"
  env               = local.env
  vpc_id            = module.vpc.vpc_id
  private_subnets   = module.vpc.private_subnets
  broker_instance_type = "kafka.t3.small"
  broker_count      = 2
  tags              = local.common_tags
}

module "monitoring" {
  source       = "../../modules/monitoring"
  env          = local.env
  cluster_name = module.eks.cluster_name
  vpc_id       = module.vpc.vpc_id
  tags         = local.common_tags
  depends_on   = [module.eks]
}
