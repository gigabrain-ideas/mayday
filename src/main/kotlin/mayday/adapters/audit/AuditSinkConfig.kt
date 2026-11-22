package mayday.adapters.audit

import mayday.agents.ClientAuth
import mayday.agents.CredentialMode
import mayday.agents.CredentialProviderFactory
import mayday.agents.CredentialSpec
import mayday.agents.OAuthGrant
import mayday.audit.AuditSink
import java.net.URI
import java.nio.file.Path

/** Sink settings from the MAYDAY_AUDIT_* environment variables. See docs/domains/audit.md. */
data class AuditSinkConfig(
    val sinks: List<String> = listOf("stdout"),
    val file: String = "",
    val httpUrl: String = "",
    val httpHeaders: Map<String, String> = emptyMap(),
    val httpFormat: String = "json",
    val lokiUrl: String = "",
    val lokiTenant: String = "",
    val lokiUser: String = "",
    val lokiPassword: String = "",
    val lokiToken: String = "",
    val lokiLabels: Map<String, String> = emptyMap(),
    val sentinelEndpoint: String = "",
    val sentinelDcrId: String = "",
    val sentinelStream: String = "Custom-MaydayAudit_CL",
    val sentinelTenantId: String = "",
    val sentinelClientId: String = "",
    val sentinelClientSecret: String = "",
    val sentinelAssertionFile: String = "",
    val sentinelAuthority: String = "https://login.microsoftonline.com",
) {
    fun build(credentials: CredentialProviderFactory): List<AuditSink> = sinks.map { it.trim().lowercase() }.filter { it.isNotEmpty() && it != "none" }.distinct().map { name ->
        when (name) {
            "stdout" -> StdoutSink()
            "file" -> FileSink(Path.of(require("MAYDAY_AUDIT_FILE", file)))
            "http" -> HttpSink(uri("MAYDAY_AUDIT_HTTP_URL", httpUrl), httpHeaders, ndjson = httpFormat.equals("ndjson", true))
            "loki" -> {
                val auth = when {
                    lokiToken.isNotEmpty() -> mapOf("Authorization" to "Bearer $lokiToken")
                    lokiUser.isNotEmpty() -> mapOf("Authorization" to LokiSink.basic(lokiUser, lokiPassword))
                    else -> emptyMap()
                }
                LokiSink(uri("MAYDAY_AUDIT_LOKI_URL", lokiUrl), lokiTenant, auth, lokiLabels)
            }
            "sentinel" -> {
                val tenant = require("MAYDAY_AUDIT_SENTINEL_TENANT_ID", sentinelTenantId)
                val spec = CredentialSpec(
                    mode = CredentialMode.OAUTH2,
                    tokenUrl = "${sentinelAuthority.trimEnd('/')}/$tenant/oauth2/v2.0/token",
                    grant = OAuthGrant.CLIENT_CREDENTIALS,
                    clientAuth = ClientAuth.POST,
                    clientId = require("MAYDAY_AUDIT_SENTINEL_CLIENT_ID", sentinelClientId),
                    clientSecret = sentinelClientSecret,
                    assertionFile = sentinelAssertionFile,
                    scope = "https://monitor.azure.com//.default",
                )
                require(spec.complete) { "MAYDAY_AUDIT_SENTINEL_CLIENT_SECRET or MAYDAY_AUDIT_SENTINEL_ASSERTION_FILE is required" }
                SentinelSink(uri("MAYDAY_AUDIT_SENTINEL_ENDPOINT", sentinelEndpoint), require("MAYDAY_AUDIT_SENTINEL_DCR_ID", sentinelDcrId),
                    sentinelStream, credentials.create(spec))
            }
            else -> throw IllegalArgumentException("MAYDAY_AUDIT_SINKS: unknown sink '$name'. Use stdout, file, http, loki, sentinel or none.")
        }
    }

    private fun require(variable: String, value: String): String {
        require(value.isNotBlank()) { "$variable is required for the selected audit sink" }
        return value.trim()
    }

    private fun uri(variable: String, value: String): URI {
        val u = runCatching { URI(require(variable, value)) }.getOrNull()
        require(u != null && u.scheme in setOf("http", "https") && !u.host.isNullOrBlank()) { "$variable must be an http(s) URL" }
        return u
    }
}
