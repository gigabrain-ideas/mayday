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
    secretKey                 = random_password.secret_key.result
    oidcClientSecret          = var.oidc_client_secret
    anthropicApiKey           = var.anthropic_api_key
    databasePassword          = var.database_password
    llmApiKey                 = var.llm_api_key
    llmClientSecret           = var.llm_client_secret
    auditKey                  = var.audit_key
    auditLokiPassword         = var.audit_loki_password
    auditLokiToken            = ""
    auditSentinelClientSecret = var.audit_sentinel_client_secret
    auditHttpHeaders          = var.audit_http_headers
    pagingWebhookSecret       = var.paging_webhook_secret
    pagingWebhookHeaders      = var.paging_webhook_headers
  }
}

locals {
  secret_name = kubernetes_secret_v1.mayday.metadata[0].name
}

resource "helm_release" "mayday" {
  name      = "mayday"
  namespace = kubernetes_namespace_v1.mayday.metadata[0].name
  chart     = "${path.module}/../helm/mayday"

  values = [yamlencode({
    image          = { repository = var.image_repository, tag = var.image_tag }
    replicaCount   = var.database_url == "" ? 1 : var.replicas
    publicUrl      = var.public_url
    existingSecret = local.secret_name
    adminEmails    = join(",", var.admin_emails)
    database       = { url = var.database_url, user = var.database_user }
    oidc           = { issuer = var.oidc_issuer, clientId = var.oidc_client_id, groupsClaim = var.oidc_groups_claim }
    groups         = { autoCreate = var.groups_auto_create, filter = var.groups_filter, ownerSuffix = var.groups_owner_suffix }
    persistence    = { enabled = var.database_url == "" }
    autoscaling    = { enabled = var.database_url != "", minReplicas = var.replicas, maxReplicas = var.max_replicas }
    ingress = {
      enabled   = var.ingress_host != ""
      host      = var.ingress_host
      className = var.ingress_class
      tls       = var.ingress_tls_secret == "" ? [] : [{ hosts = [var.ingress_host], secretName = var.ingress_tls_secret }]
    }
    grpc = {
      enabled = var.grpc_enabled
      ingress = {
        enabled = var.grpc_enabled && var.grpc_ingress_host != ""
        host    = var.grpc_ingress_host
        tls     = var.ingress_tls_secret == "" ? [] : [{ hosts = [var.grpc_ingress_host], secretName = var.ingress_tls_secret }]
      }
    }
    llm = {
      provider           = var.llm_provider
      baseUrl            = var.llm_base_url
      model              = var.llm_model
      authStyle          = var.llm_auth_style
      credential         = var.llm_credential
      issuer             = var.llm_issuer
      tokenUrl           = var.llm_token_url
      grant              = var.llm_grant
      clientAuth         = var.llm_client_auth
      clientId           = var.llm_client_id
      scope              = var.llm_scope
      tokenParams        = var.llm_token_params
      headers            = var.llm_headers
      apiKeySecret       = { name = var.llm_api_key == "" ? "" : local.secret_name, key = "llmApiKey" }
      clientSecretSecret = { name = var.llm_client_secret == "" ? "" : local.secret_name, key = "llmClientSecret" }
    }
    workloadIdentity = { enabled = var.workload_identity_audience != "", audience = var.workload_identity_audience }
    serviceAccount   = { create = length(var.service_account_annotations) > 0, name = "", annotations = var.service_account_annotations }
    podLabels        = var.pod_labels
    tokenProxy       = { enabled = var.token_proxy_upstream != "", upstream = var.token_proxy_upstream }
    otel             = { endpoint = var.otel_endpoint, sampling = var.otel_sampling, captureContent = var.otel_capture_content }
    traceUrlTemplate = var.trace_url_template
    paging           = { webhookUrl = var.paging_webhook_url }
    audit = {
      sinks = join(",", var.audit_sinks)
      reads = var.audit_reads
      http  = { url = var.audit_http_url, format = var.audit_http_format }
      loki  = { url = var.audit_loki_url, tenant = var.audit_loki_tenant, user = var.audit_loki_user, labels = var.audit_loki_labels }
      sentinel = {
        endpoint = var.audit_sentinel_endpoint, dcrId = var.audit_sentinel_dcr_id, stream = var.audit_sentinel_stream
        tenantId = var.audit_sentinel_tenant_id, clientId = var.audit_sentinel_client_id
      }
    }
  })]
}
