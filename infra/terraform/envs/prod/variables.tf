variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.2.0.0/16"
}

variable "azs" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "private_subnets" {
  type    = list(string)
  default = ["10.2.1.0/24", "10.2.2.0/24", "10.2.3.0/24"]
}

variable "public_subnets" {
  type    = list(string)
  default = ["10.2.101.0/24", "10.2.102.0/24", "10.2.103.0/24"]
}

variable "kubernetes_version" {
  type    = string
  default = "1.29"
}

variable "node_instance_types" {
  type    = list(string)
  default = ["m5.large"]
}

variable "desired_size" {
  type    = number
  default = 3
}

variable "min_size" {
  type    = number
  default = 3
}

variable "max_size" {
  type    = number
  default = 9
}

variable "db_name" {
  type    = string
  default = "transactions"
}

variable "rds_instance_class" {
  type    = string
  default = "db.r5.large"
}
