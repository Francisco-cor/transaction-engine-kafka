output "prometheus_release" {
  value = var.enable_prometheus ? helm_release.prometheus[0].name : null
}

output "jaeger_release" {
  value = var.enable_jaeger ? helm_release.jaeger[0].name : null
}
