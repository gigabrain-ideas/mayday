package mayday.adapters.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

private const val ANTHROPIC_URL = "https://api.anthropic.com"
private const val ANTHROPIC_VERSION = "2026-10-21"

/** Models that accept server-side refusal fallbacks. */
private val FALLBACK_MODELS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

/**
 * Anthropic Messages API on java.net.http. The assistant content of a response goes back to the API unchanged ([LlmMessage.raw]),
 * so blocks this adapter does not read (thinking with its signature) survive the tool loop.
 */
class AnthropicProvider internal constructor(
    private val client: HttpClient,
    private val config: LlmConfig,
    auth: GatewayAuth,
    telemetry: Telemetry,
    retry: RetryPolicy,
) : LlmProvider {
    private val caller = GatewayCaller(auth, telemetry, retry)
    private val endpoint = URI.create(config.baseUrl.trim().trimEnd('/').ifEmpty { ANTHROPIC_URL } + "/v1/messages")
    private val fallbacks = config.model in FALLBACK_MODELS
    private val protocol = buildMap {
        put("anthropic-version", ANTHROPIC_VERSION)
        if (fallbacks) put("anthropic-beta", "server-side-fallback-2026-07-01")
    }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val body = Json.encodeToString(JsonObject.serializer(), body(request))
        val resp = caller.call { headers -> postJson(client, endpoint, body, protocol + headers) }
        val json = runCatching { Json.parseToJsonElement(resp).jsonObject }.getOrElse { throw GatewayError(200, "AI gateway answered with invalid JSON") }
        return toResponse(json)
    }

    private fun body(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", config.model)
        put("max_tokens", request.maxTokens)
        if (request.system.isNotEmpty()) put("system", request.system)
        putJsonObject("output_config") { put("effort", config.effort) }
        if (fallbacks) put("fallbacks", "default")
        if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
            request.tools.forEach { spec ->
                addJsonObject {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("input_schema", if ("type" in spec.inputSchema) spec.inputSchema else JsonObject(spec.inputSchema + ("type" to JsonPrimitive("object"))))
                }
            }
        })
        put("messages", buildJsonArray { request.messages.forEach { add(message(it)) } })
    }

    private fun message(m: LlmMessage): JsonObject = buildJsonObject {
        put("role", if (m.role == LlmRole.ASSISTANT) "assistant" else "user")
        put("content", (m.raw as? JsonArray)?.takeIf { m.role == LlmRole.ASSISTANT } ?: buildJsonArray { m.blocks.forEach { add(block(it)) } })
    }

    private fun block(b: LlmBlock): JsonObject = when (b) {
        is LlmBlock.Text -> buildJsonObject { put("type", "text"); put("text", b.text) }
        is LlmBlock.ToolUse -> buildJsonObject { put("type", "tool_use"); put("id", b.id); put("name", b.name); put("input", b.input) }
        is LlmBlock.ToolResult -> buildJsonObject { put("type", "tool_result"); put("tool_use_id", b.toolUseId); put("content", b.content); put("is_error", b.isError) }
    }

    private fun toResponse(json: JsonObject): LlmResponse {
        val content = json["content"] as? JsonArray ?: throw GatewayError(200, "AI gateway answered without content")
        val blocks = content.mapNotNull { el ->
            val b = el as? JsonObject ?: return@mapNotNull null
            when (b.string("type")) {
                "text" -> LlmBlock.Text(b.string("text").orEmpty())
                "tool_use" -> LlmBlock.ToolUse(b.string("id").orEmpty(), b.string("name").orEmpty(), b["input"] as? JsonObject ?: JsonObject(emptyMap()))
                else -> null
            }
        }
        val stop = when (json.string("stop_reason")) {
            "end_turn", "stop_sequence" -> StopReason.END
            "tool_use" -> StopReason.TOOL_USE
            "max_tokens" -> StopReason.MAX_TOKENS
            "refusal" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }
        val u = json["usage"] as? JsonObject
        val input = listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens").sumOf { u?.long(it) ?: 0L }
        return LlmResponse(LlmMessage(LlmRole.ASSISTANT, blocks, content), stop, LlmUsage(input, u?.long("output_tokens") ?: 0), json.string("model").orEmpty())
    }
}
