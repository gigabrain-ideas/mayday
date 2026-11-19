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
