public_url        = "https://mayday.example.com"
ingress_host      = "mayday.example.com"
# grpc_ingress_host = "grpc.mayday.example.com"  # native gRPC; set ingress_tls_secret too
admin_emails      = ["you@example.com"]
database_url      = "postgresql://postgres.internal:5432/mayday"
database_user     = "mayday"
oidc_issuer       = "https://accounts.example.com"
oidc_client_id    = "mayday"

# Enterprise AI gateway with Kubernetes workload identity and any OAuth 2.0 provider. See docs/deployment.md.
# llm_provider               = "openai"
# llm_base_url               = "https://ai-gateway.example.com/v1"
# llm_model                  = "gpt-gateway"
# llm_issuer                 = "https://idp.example.com"
# llm_client_id              = "mayday"
# llm_scope                  = "ai-gateway.invoke"
# llm_headers                = { "x-company-agent-id" = "mayday-prod" }
# workload_identity_audience = "https://idp.example.com"
# token_proxy_upstream       = "https://ai-gateway.example.com"  # optional sidecar; then llm_base_url is not used

# Tracing
# otel_endpoint      = "http://otel-collector.observability:4318"
# otel_sampling      = "0.1"
# trace_url_template = "https://grafana.example.com/explore?traceId={traceId}"

# Audit stream to Microsoft Sentinel and stdout
# audit_sinks              = ["stdout", "sentinel"]
# audit_sentinel_endpoint  = "https://mayday-abcd.westeurope-1.ingest.monitor.azure.com"
# audit_sentinel_dcr_id    = "dcr-00000000000000000000000000000000"
# audit_sentinel_tenant_id = "00000000-0000-0000-0000-000000000000"
# audit_sentinel_client_id = "00000000-0000-0000-0000-000000000000"
