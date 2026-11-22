package mayday.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mayday.agents.ToolCatalog
import mayday.agents.ToolOutcome
import mayday.audit.AuditCategory
import mayday.audit.AuditContext
import mayday.audit.AuditEntry
import mayday.audit.AuditOutcome
import mayday.audit.AuditSource
import mayday.audit.AuditTrail
import mayday.identity.Principal
import mayday.identity.audit

/**
 * Records each tool call (MCP and built-in agent) with the actor, the person the agent acts for, the run and the result.
 * Only identifiers and enumerated values of the arguments go to the stream, never free text.
 */
class AuditedCatalog(private val inner: ToolCatalog, private val audit: AuditTrail) : ToolCatalog {
    override suspend fun tools(principal: Principal, internal: Boolean) = inner.tools(principal, internal)

    override suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome {
        val ctx = currentCoroutineContext()[AuditContext]
        val source = if (internal) AuditSource("internal-agent") else ctx?.source ?: AuditSource("mcp")
        val by = principal.audit(ctx?.caller?.credential)
        val write = inner.tools(principal, internal).firstOrNull { it.name == name }?.write
        val details = buildMap {
            put("tool", name)
            if (write == null) put("visible", "false") else put("write", write.toString())
            putAll(arguments(args))
        }
        val out = try {
            inner.call(principal, name, args, internal)
        } catch (e: Exception) {
            audit.emit(AuditEntry(AuditCategory.AUTHORIZATION, "tool:$name", by,
                AuditOutcome.FAILURE, details = details, reason = "error", source = source))
            throw e
        }
        val outcome = when {
            !out.isError -> AuditOutcome.SUCCESS
            out.code in DENIALS -> AuditOutcome.DENIED
            else -> AuditOutcome.FAILURE
        }
        audit.emit(AuditEntry(AuditCategory.AUTHORIZATION, "tool:$name", by, outcome, details = details,
            reason = if (out.isError) out.code ?: "tool_error" else null, source = source, read = write == false))
        return out
    }

    companion object {
        /** The MCP server refused a tool that the principal cannot see. */
        suspend fun refused(audit: AuditTrail, principal: Principal, name: String, args: JsonObject) {
            val ctx = currentCoroutineContext()[AuditContext]
            audit.emit(AuditEntry(AuditCategory.AUTHORIZATION, "tool:$name", principal.audit(ctx?.caller?.credential), AuditOutcome.DENIED,
                details = mapOf("tool" to name, "visible" to "false") + arguments(args), reason = "not_allowed", source = ctx?.source ?: AuditSource("mcp")))
        }

        val DENIALS = setOf("forbidden", "unauthenticated", "locked")
        private val RECORDED = setOf("id", "ref", "team", "teams", "scope", "key", "role", "severity", "status", "enforcement", "value", "state", "fingerprint", "owner")

        fun arguments(args: JsonObject): Map<String, String> = args.entries.asSequence()
            .filter { (k, _) -> k in RECORDED || k.endsWith("_id") }
            .mapNotNull { (k, v) ->
                val text = when (v) {
                    is JsonPrimitive -> v.content
                    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(",")
                    else -> null
                } ?: return@mapNotNull null
                "arg.$k" to text.take(200)
            }
            .take(12)
            .toMap()
    }
}
