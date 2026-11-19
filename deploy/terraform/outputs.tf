output "url" { value = var.public_url }
output "namespace" { value = kubernetes_namespace_v1.mayday.metadata[0].name }
output "grpc_endpoint" { value = var.grpc_enabled && var.grpc_ingress_host != "" ? "${var.grpc_ingress_host}:443" : null }
