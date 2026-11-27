package mayday.adapters.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mayday.agents.GatewayAuth
import mayday.agents.GatewayError
import mayday.agents.LlmBlock
import mayday.agents.LlmConfig
import mayday.agents.LlmMessage
import mayday.agents.LlmProvider
import mayday.agents.LlmRequest
import mayday.agents.LlmResponse
import mayday.agents.LlmRole
import mayday.agents.LlmUsage
import mayday.agents.StopReason
import mayday.shared.Telemetry
import java.net.URI
import java.net.http.HttpClient

private const val OPENAI_URL = "https://api.openai.com/v1"
private val ROUND_TRIP_KEYS = setOf("role", "content", "tool_calls", "refusal")

/** OpenAI-compatible Chat Completions adapter on java.net.http. No reasoning parameters: gateways and non-reasoning models reject them. */
class OpenAiProvider internal constructor(
    private val client: HttpClient,
    private val config: LlmConfig,
    auth: GatewayAuth,
    telemetry: Telemetry,
    retry: RetryPolicy,
) : LlmProvider {
    private val caller = GatewayCaller(auth, telemetry, retry)
    private val endpoint = URI.create(config.baseUrl.trim().trimEnd('/').ifEmpty { OPENAI_URL } + "/chat/completions")

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val body = Json.encodeToString(JsonObject.serializer(), body(request))
        val resp = caller.call { headers -> postJson(client, endpoint, body, headers) }
        val json = runCatching { Json.parseToJsonElement(resp).jsonObject }.getOrElse { throw GatewayError(200, "AI gateway answered with invalid JSON") }
        return toResponse(json)
    }

    private fun body(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", config.model)
        put("max_completion_tokens", request.maxTokens)
        put("messages", buildJsonArray {
            if (request.system.isNotEmpty()) addJsonObject { put("role", "system"); put("content", request.system) }
            request.messages.forEach { m -> messages(m).forEach { add(it) } }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { spec ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", if ("type" in spec.inputSchema) spec.inputSchema else JsonObject(spec.inputSchema + ("type" to JsonPrimitive("object"))))
                        }
                    }
                }
            })
        }
    }

    private fun messages(m: LlmMessage): List<JsonObject> {
        if (m.role == LlmRole.ASSISTANT) {
            if (m.raw is JsonObject) return listOf(m.raw)
            val text = m.blocks.filterIsInstance<LlmBlock.Text>().joinToString("\n") { it.text }
            val uses = m.blocks.filterIsInstance<LlmBlock.ToolUse>()
            return listOf(buildJsonObject {
                put("role", "assistant")
                put("content", text.ifEmpty { null })
                if (uses.isNotEmpty()) put("tool_calls", buildJsonArray {
                    uses.forEach { u ->
                        addJsonObject {
                            put("id", u.id)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", u.name)
                                put("arguments", Json.encodeToString(JsonObject.serializer(), u.input))
                            }
                        }
                    }
                })
            })
        }
        val out = ArrayList<JsonObject>()
        m.blocks.filterIsInstance<LlmBlock.ToolResult>().forEach { r ->
            out += buildJsonObject {
                put("role", "tool")
                put("tool_call_id", r.toolUseId)
                put("content", if (r.isError) "Error: ${r.content}" else r.content)
            }
        }
        val text = m.blocks.filterIsInstance<LlmBlock.Text>().joinToString("\n") { it.text }
        if (text.isNotEmpty() || out.isEmpty()) out += buildJsonObject { put("role", "user"); put("content", text) }
        return out
    }

    private fun toResponse(json: JsonObject): LlmResponse {
        val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw GatewayError(200, "AI gateway answered without choices")
        val message = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
        val blocks = ArrayList<LlmBlock>()
        message.string("content")?.takeIf { it.isNotEmpty() }?.let { blocks += LlmBlock.Text(it) }
        val refusal = message.string("refusal")?.takeIf { it.isNotEmpty() }
        if (refusal != null && blocks.isEmpty()) blocks += LlmBlock.Text(refusal)
        (message["tool_calls"] as? JsonArray)?.forEach { call ->
            val c = call as? JsonObject ?: return@forEach
            val fn = c["function"] as? JsonObject ?: return@forEach
            val args = runCatching { Json.parseToJsonElement(fn.string("arguments").orEmpty()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            blocks += LlmBlock.ToolUse(c.string("id").orEmpty(), fn.string("name").orEmpty(), args)
        }
        val hasTools = blocks.any { it is LlmBlock.ToolUse }
        val stop = when (choice.string("finish_reason")) {
            "length" -> StopReason.MAX_TOKENS
            "content_filter" -> StopReason.REFUSAL
            "tool_calls", "function_call" -> StopReason.TOOL_USE
            "stop" -> when {
                refusal != null -> StopReason.REFUSAL
                hasTools -> StopReason.TOOL_USE
                else -> StopReason.END
            }
            else -> if (hasTools) StopReason.TOOL_USE else StopReason.OTHER
        }
        val usage = json["usage"] as? JsonObject
        val raw = JsonObject(message.filter { (k, v) -> k in ROUND_TRIP_KEYS && v !is JsonNull || k == "content" } + ("role" to JsonPrimitive("assistant")))
        return LlmResponse(
            LlmMessage(LlmRole.ASSISTANT, blocks, raw),
            stop,
            LlmUsage(usage?.long("prompt_tokens") ?: 0, usage?.long("completion_tokens") ?: 0),
            json.string("model").orEmpty(),
        )
    }
}
