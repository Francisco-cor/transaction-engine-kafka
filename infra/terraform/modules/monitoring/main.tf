resource "helm_release" "prometheus" {
  count      = var.enable_prometheus ? 1 : 0
  name       = "prometheus"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = "61.3.0"
  namespace  = "monitoring"
  create_namespace = true
  values = [
    yamlencode({
      prometheus = {
        prometheusSpec = {
          retention = "7d"
          additionalScrapeConfigs = [
            {
              job_name = "transaction-service"
              static_configs = [{ targets = ["transaction-engine-transaction-service:8080"] }]
              metrics_path = "/actuator/prometheus"
            }
          ]
        }
      }
      grafana = { enabled = var.enable_grafana }
    })
  ]
  tags = var.tags
}

resource "helm_release" "jaeger" {
  count      = var.enable_jaeger ? 1 : 0
  name       = "jaeger"
  repository = "https://jaegertracing.github.io/helm-charts"
  chart      = "jaeger"
  version    = "0.71.5"
  namespace  = "observability"
  create_namespace = true
  set {
    name  = "provisionDataStore.cassandra"
    value = "false"
  }
  set {
    name  = "storage.type"
    value = "memory"
  }
}

resource "aws_cloudwatch_log_group" "eks" {
  name              = "/aws/eks/${var.cluster_name}/cluster"
  retention_in_days = 7
  tags              = var.tags
}
