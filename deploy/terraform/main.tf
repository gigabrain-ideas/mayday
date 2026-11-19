terraform {
  required_version = ">= 1.6"
  required_providers {
    helm       = { source = "hashicorp/helm", version = ">= 3.0" }
    kubernetes = { source = "hashicorp/kubernetes", version = ">= 2.25" }
    random     = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

provider "kubernetes" {
  config_path    = var.kubeconfig
  config_context = var.kube_context
}

provider "helm" {
  kubernetes = {
    config_path    = var.kubeconfig
    config_context = var.kube_context
  }
}

resource "random_password" "secret_key" {
  length  = 64
  special = false
}

resource "kubernetes_namespace_v1" "mayday" {
  metadata { name = var.namespace }
}

resource "kubernetes_secret_v1" "mayday" {
  metadata {
    name      = "mayday"
    namespace = kubernetes_namespace_v1.mayday.metadata[0].name
  }
  data = {
    secretKey        = random_password.secret_key.result
    oidcClientSecret = var.oidc_client_secret
    anthropicApiKey  = var.anthropic_api_key
    databasePassword = var.database_password
  }
}

resource "helm_release" "mayday" {
  name      = "mayday"
  namespace = kubernetes_namespace_v1.mayday.metadata[0].name
  chart     = "${path.module}/../helm/mayday"

  values = [yamlencode({
    image          = { repository = var.image_repository, tag = var.image_tag }
    replicaCount   = var.database_url == "" ? 1 : var.replicas
    publicUrl      = var.public_url
    existingSecret = kubernetes_secret_v1.mayday.metadata[0].name
    adminEmails    = join(",", var.admin_emails)
    database       = { url = var.database_url, user = var.database_user }
    oidc           = { issuer = var.oidc_issuer, clientId = var.oidc_client_id }
    persistence    = { enabled = var.database_url == "" }
    autoscaling    = { enabled = var.database_url != "", minReplicas = var.replicas, maxReplicas = var.max_replicas }
    ingress = {
      enabled   = var.ingress_host != ""
      host      = var.ingress_host
      className = var.ingress_class
      tls       = var.ingress_tls_secret == "" ? [] : [{ hosts = [var.ingress_host], secretName = var.ingress_tls_secret }]
    }
  })]
}
