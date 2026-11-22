package mayday.adapters.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mayday.agents.ToolCatalog
import mayday.audit.AuditTrail
import mayday.tools.AuditedCatalog
import mayday.identity.Principal
import mayday.shared.Telemetry

/** MCP server (JSON-RPC 2.0). Transport independent: the HTTP adapter feeds it request bodies. */
class McpServer(
    private val catalog: ToolCatalog,
    private val version: String,
    private val telemetry: Telemetry = Telemetry.noop,
    private val audit: AuditTrail = AuditTrail.none,
) {
    companion object {
        const val PROTOCOL = "2025-11-25"
        private val ERROR_CODE = Regex("^[a-z_]{1,40}$")
        val SUPPORTED = setOf("2025-11-25", "2025-06-18", "2025-03-26")
        const val INSTRUCTIONS = "Mayday incident management. Read tools are always available. Write tools follow the RBAC of the identity in the token; " +
            "when you act for a person, use the delegated token Mayday gave you. Write access can be turned off per team by the 'mcp.write_enabled' setting."
    }

    /** Returns null for notifications. */
    suspend fun handle(principal: Principal, body: JsonElement): JsonElement? = when (body) {
        is JsonArray -> buildJsonArray { body.forEach { m -> handleOne(principal, m)?.let { add(it) } } }.takeIf { it.isNotEmpty() }
        else -> handleOne(principal, body)
    }

    private suspend fun handleOne(principal: Principal, msg: JsonElement): JsonElement? {
        val o = msg as? JsonObject ?: return error(JsonNull, -32600, "Invalid request")
        val id = o["id"]
        val method = (o["method"] as? JsonPrimitive)?.content ?: return error(id ?: JsonNull, -32600, "Invalid request")
        if (id == null) return null
        val params = o["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            when (method) {
                "initialize" -> result(id, buildJsonObject {
                    val asked = (params["protocolVersion"] as? JsonPrimitive)?.content
                    put("protocolVersion", if (asked in SUPPORTED) asked else PROTOCOL)
                    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
                    putJsonObject("serverInfo") { put("name", "mayday"); put("title", "Mayday! Mayday!"); put("version", version) }
                    put("instructions", INSTRUCTIONS)
                })
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> { val tools = catalog.tools(principal, internal = false); result(id, buildJsonObject {
                    putJsonArray("tools") {
                        tools.forEach { t ->
                            add(buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("inputSchema", t.inputSchema)
                                putJsonObject("annotations") {
                                    put("readOnlyHint", !t.write)
                                    put("destructiveHint", false)
                                }
                            })
                        }
                    }
                }) }
                "tools/call" -> {
                    val name = params["name"]?.jsonPrimitive?.content ?: return error(id, -32602, "Missing tool name")
                    val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                    callTool(principal, id, name, args)
                }
                "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
                "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            error(id, -32603, e.message ?: "Internal error")
        }
    }

    private suspend fun callTool(principal: Principal, id: JsonElement, name: String, args: JsonObject): JsonElement =
        telemetry.span("tools/call $name", mapOf(
            "mcp.method.name" to "tools/call",
            "gen_ai.operation.name" to "execute_tool",
            "gen_ai.tool.name" to name,
            "jsonrpc.request.id" to (id as? JsonPrimitive)?.content,
            "mayday.actor.kind" to principal.actor.kind.name,
            "mayday.actor.delegated" to principal.isDelegated,
            "mayday.run.id" to principal.runId,
        )) { span ->
            if (telemetry.captureContent) span.set("gen_ai.tool.call.arguments", args.toString().take(8000))
            if (catalog.tools(principal, internal = false).none { it.name == name }) {
                span.fail("Unknown tool or not allowed")
                span.set("error.type", "-32602")
                AuditedCatalog.refused(audit, principal, name, args)
                return@span error(id, -32602, "Unknown tool or not allowed: $name")
            }
            val out = catalog.call(principal, name, args, internal = false)
            if (out.isError) {
                span.fail(out.content.take(500))
                span.set("error.type", out.content.substringBefore(':').takeIf { ERROR_CODE.matches(it) } ?: "tool_error")
            }
            result(id, buildJsonObject {
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", out.content) }) }
                put("isError", out.isError)
            })
        }

    private fun result(id: JsonElement, r: JsonObject) = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", r) }
    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message) }
    }
}
