output "url" { value = var.public_url }
output "namespace" { value = kubernetes_namespace_v1.mayday.metadata[0].name }
