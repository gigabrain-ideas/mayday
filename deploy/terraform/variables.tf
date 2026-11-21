variable "kubeconfig" {
  type    = string
  default = "~/.kube/config"
}
variable "kube_context" {
  type    = string
  default = null
}
variable "namespace" {
  type    = string
  default = "mayday"
}
variable "image_repository" {
  type    = string
  default = "ghcr.io/your-org/mayday"
}
variable "image_tag" {
  type    = string
  default = "0.1.0"
}
variable "public_url" { type = string }
variable "replicas" {
  type    = number
  default = 2
}
variable "max_replicas" {
  type    = number
  default = 10
}
variable "admin_emails" {
  type    = list(string)
  default = []
}
variable "database_url" {
  description = "postgresql://host:5432/db. Empty uses the single-replica journal store."
  type        = string
  default     = ""
}
variable "database_user" {
  type    = string
  default = ""
}
variable "database_password" {
  type      = string
  default   = ""
  sensitive = true
}
variable "oidc_issuer" {
  type    = string
  default = ""
}
variable "oidc_client_id" {
  type    = string
  default = ""
}
variable "oidc_groups_claim" {
  type        = string
  default     = "groups"
  description = "Claim with the groups of the person. Empty turns group sync from sign-in off."
}
variable "groups_auto_create" {
  type        = bool
  default     = false
  description = "Identity provider groups that no team links to create teams."
}
variable "groups_filter" {
  type        = string
  default     = ""
  description = "Regular expression. Only matching groups create teams."
}
variable "groups_owner_suffix" {
  type        = string
  default     = ""
  description = "Group <name><suffix> is the owner group of the team of group <name>."
}
variable "oidc_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}
variable "anthropic_api_key" {
  type      = string
  default   = ""
  sensitive = true
}
variable "ingress_host" {
  type    = string
  default = ""
}
variable "ingress_class" {
  type    = string
  default = "nginx"
}
variable "ingress_tls_secret" {
  type    = string
  default = ""
}
variable "grpc_enabled" {
  description = "Expose native gRPC (port 9090) in the service. Browsers use gRPC-Web on the HTTP port."
  type        = bool
  default     = true
}
variable "grpc_ingress_host" {
  description = "Host for native gRPC through the ingress. Empty = no gRPC ingress. Needs TLS (ingress_tls_secret must cover this host)."
  type        = string
  default     = ""
}

# ---- Internal agent (LLM provider). An empty value leaves the setting to the web interface. ----
variable "llm_provider" {
  description = "anthropic or openai (OpenAI-compatible API)."
  type        = string
  default     = ""
}
variable "llm_base_url" {
  description = "Base URL of the provider or AI gateway, for example https://ai-gateway.example.com/v1."
  type        = string
  default     = ""
}
variable "llm_model" {
  type    = string
  default = ""
}
variable "llm_auth_style" {
  description = "api_key (x-api-key) or bearer (Authorization: Bearer). Empty follows the provider."
  type        = string
  default     = ""
}
variable "llm_credential" {
  description = "none, static, oauth2 or file. Empty with workload_identity_audience set: oauth2."
  type        = string
  default     = ""
}
variable "llm_api_key" {
  description = "API key for llm_credential = static."
  type        = string
  default     = ""
  sensitive   = true
}
variable "llm_issuer" {
  description = "OIDC issuer. Mayday reads the token endpoint from its metadata."
  type        = string
  default     = ""
}
variable "llm_token_url" {
  description = "OAuth 2.0 token endpoint, when the issuer publishes no metadata."
  type        = string
  default     = ""
}
variable "llm_grant" {
  description = "client_credentials (default) or token_exchange (RFC 8693)."
  type        = string
  default     = ""
}
variable "llm_client_auth" {
  description = "basic (default, client_secret_basic) or post (client_secret_post)."
  type        = string
  default     = ""
}
variable "llm_client_id" {
  type    = string
  default = ""
}
variable "llm_client_secret" {
  description = "OAuth 2.0 client secret. Leave empty for workload identity."
  type        = string
  default     = ""
  sensitive   = true
}
variable "llm_scope" {
  description = "OAuth 2.0 scope, for example ai-gateway.invoke."
  type        = string
  default     = ""
}
variable "llm_token_params" {
  description = "Extra OAuth 2.0 token request fields, for example { audience = \"https://ai-gateway.example.com\" }."
  type        = map(string)
  default     = {}
}
variable "llm_headers" {
  description = "Custom headers for each gateway call, for example { \"x-company-agent-id\" = \"mayday-prod\" }."
  type        = map(string)
  default     = {}
}
variable "workload_identity_audience" {
  description = "Audience of the projected service account token for workload identity federation. Empty turns it off."
  type        = string
  default     = ""
}
variable "service_account_annotations" {
  description = "Annotations of the service account that Mayday creates, for example for your cloud's workload identity. Empty: no service account is created."
  type        = map(string)
  default     = {}
}
variable "pod_labels" {
  description = "Extra pod labels."
  type        = map(string)
  default     = {}
}
variable "token_proxy_upstream" {
  description = "AI gateway URL for the token-proxy sidecar. Empty turns the sidecar off."
  type        = string
  default     = ""
}

# ---- Tracing ----
variable "otel_endpoint" {
  description = "OTLP/HTTP endpoint, for example http://otel-collector.observability:4318. Empty turns tracing off."
  type        = string
  default     = ""
}
variable "otel_sampling" {
  description = "Ratio of traces to keep, 0.0 to 1.0. Empty uses the Mayday default."
  type        = string
  default     = ""
}
variable "otel_capture_content" {
  description = "Export prompts, responses and tool arguments. They can contain incident data."
  type        = bool
  default     = false
}
variable "trace_url_template" {
  description = "Link from a run to its trace. {traceId} is the placeholder."
  type        = string
  default     = ""
}
