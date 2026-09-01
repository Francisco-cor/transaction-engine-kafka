variable "env" {
  type = string
}

variable "cluster_name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "enable_prometheus" {
  type    = bool
  default = true
}

variable "enable_grafana" {
  type    = bool
  default = true
}

variable "enable_jaeger" {
  type    = bool
  default = true
}

variable "tags" {
  type    = map(string)
  default = {}
}
