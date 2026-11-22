package mayday.adapters.http

import kotlinx.coroutines.withContext
import mayday.App
import mayday.audit.AuditActor
import mayday.audit.AuditCategory
import mayday.audit.AuditContext
import mayday.audit.AuditEntry
import mayday.audit.AuditOutcome
import mayday.audit.AuditSource
import mayday.audit.AuditTarget
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange

/**
 * Records each request to the HTTP API (MCP transport, alert intake, sign-in, token exchange, SCIM) with the caller and
 * the result. gRPC-Web calls are not recorded here: the gRPC audit interceptor records them.
 */
@Component
class AuditFilter(private val app: App) : CoWebFilter() {
    override suspend fun filter(exchange: ServerWebExchange, chain: CoWebFilterChain) {
        val path = exchange.request.path.pathWithinApplication().value()
        if (AUDITED.none { path.startsWith(it) }) return chain.filter(exchange)
        val req = exchange.request
        val channel = when {
            path.startsWith("/mcp") -> "mcp"
            path.startsWith("/scim/") -> "scim"
            else -> "http"
        }
        val ctx = AuditContext(AuditSource(
            channel,
            req.remoteAddress?.let { it.address?.hostAddress ?: it.hostString },
            req.headers.getFirst("X-Forwarded-For")?.take(300),
            req.headers.getFirst("User-Agent")?.take(300),
        ))
        val traceId = app.ports.telemetry.currentTraceId().ifEmpty { null }
        var failed = false
        try {
            withContext(ctx) { chain.filter(exchange) }
        } catch (e: Exception) {
            failed = true
            throw e
        } finally {
            runCatching { record(exchange, path, ctx, traceId, failed) }
        }
    }

    private fun record(exchange: ServerWebExchange, path: String, ctx: AuditContext, traceId: String?, failed: Boolean) {
        val method = exchange.request.method
        val status = exchange.response.statusCode?.value() ?: if (failed) 500 else 200
        val pattern = exchange.getAttribute<Any>(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: path
        @Suppress("UNCHECKED_CAST")
        val vars = exchange.getAttribute<Map<String, String>>(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE).orEmpty()
        val outcome = when (status) {
            in 200..399 -> AuditOutcome.SUCCESS
            401, 403 -> AuditOutcome.DENIED
            else -> AuditOutcome.FAILURE
        }
        val reason = exchange.getAttribute<String>(REASON) ?: if (status >= 400) HttpErrors.codeOf(status) else null
        app.audit.emit(AuditEntry(
            category = AuditCategory.AUTHORIZATION,
            action = "http:${method.name()} $pattern",
            by = ctx.caller ?: AuditActor.anonymous,
            outcome = outcome,
            target = vars["id"]?.let { AuditTarget(pattern.substringBefore("/{").substringAfterLast('/').lowercase().removeSuffix("s"), it) },
            details = mapOf("status" to status.toString()) + vars.mapKeys { "path.${it.key}" },
            reason = reason,
            source = ctx.source,
            traceId = traceId,
            read = method == HttpMethod.GET || method == HttpMethod.HEAD || path == "/mcp",
        ))
    }

    companion object {
        const val REASON = "mayday.audit.reason"
        private val AUDITED = listOf("/api/", "/auth/", "/oauth/", "/mcp", "/scim/")
    }
}
