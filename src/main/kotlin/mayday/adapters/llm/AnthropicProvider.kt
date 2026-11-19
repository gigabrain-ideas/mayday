package mayday.adapters.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import mayday.agents.LlmBlock
import mayday.agents.LlmConfig
import mayday.agents.LlmMessage
import mayday.agents.LlmProvider
import mayday.agents.LlmProviderFactory
import mayday.agents.LlmRequest
import mayday.agents.LlmResponse
import mayday.agents.LlmRole
import mayday.agents.StopReason
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Models that accept server-side refusal fallbacks. */
private val FALLBACK_MODELS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

object AnthropicFactory : LlmProviderFactory {
    private val clients = ConcurrentHashMap<String, AnthropicClient>()

    override fun create(config: LlmConfig, apiKey: String): LlmProvider {
        val client = clients.computeIfAbsent("${config.baseUrl}|${apiKey.hashCode()}") {
            val b = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(10)).maxRetries(2)
            if (config.baseUrl.isNotBlank()) b.baseUrl(config.baseUrl)
            b.build()
        }
        return AnthropicProvider(client, config)
    }
}

class AnthropicProvider(private val client: AnthropicClient, private val config: LlmConfig) : LlmProvider {
    override fun complete(request: LlmRequest): LlmResponse {
        val b = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(config.effort)).build())
        if (config.model in FALLBACK_MODELS) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        request.tools.forEach { spec ->
            val props = (spec.inputSchema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
            val required = (spec.inputSchema["required"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList()
            val pb = Tool.InputSchema.Properties.builder()
            props.forEach { (k, v) -> pb.putAdditionalProperty(k, JsonValue.from(toJava(v))) }
            b.addTool(
                Tool.builder()
                    .name(spec.name)
                    .description(spec.description)
                    .inputSchema(Tool.InputSchema.builder().properties(pb.build()).required(required).build())
                    .build(),
            )
        }
        request.messages.forEach { m ->
            when {
                m.role == LlmRole.ASSISTANT && m.raw is Message -> b.addMessage(m.raw)
                m.role == LlmRole.ASSISTANT -> b.addAssistantMessage(m.blocks.filterIsInstance<LlmBlock.Text>().joinToString("\n") { it.text })
                else -> b.addUserMessageOfContentBlockParams(m.blocks.map(::toParam))
            }
        }
        val resp = client.messages().create(b.build())
        val blocks = resp.content().mapNotNull { block ->
            block.text().map<LlmBlock> { LlmBlock.Text(it.text()) }.orElse(null)
                ?: block.toolUse().map<LlmBlock> {
                    LlmBlock.ToolUse(it.id(), it.name(), (fromJava(it._input().convert(Any::class.java)) as? JsonObject) ?: JsonObject(emptyMap()))
                }.orElse(null)
        }
        val stop = when (resp.stopReason().map { it.toString() }.orElse("")) {
            "end_turn", "stop_sequence" -> StopReason.END
            "tool_use" -> StopReason.TOOL_USE
            "max_tokens" -> StopReason.MAX_TOKENS
            "refusal" -> StopReason.REFUSAL
            else -> StopReason.OTHER
        }
        return LlmResponse(LlmMessage(LlmRole.ASSISTANT, blocks, resp), stop)
    }

    private fun toParam(block: LlmBlock): ContentBlockParam = when (block) {
        is LlmBlock.Text -> ContentBlockParam.ofText(TextBlockParam.builder().text(block.text).build())
        is LlmBlock.ToolResult -> ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder().toolUseId(block.toolUseId).content(block.content).isError(block.isError).build(),
        )
        is LlmBlock.ToolUse -> throw IllegalArgumentException("Tool use blocks belong to assistant messages")
    }
}

fun toJava(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonObject -> e.mapValues { toJava(it.value) }
    is JsonArray -> e.map { toJava(it) }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull
        else -> e.doubleOrNull
    }
}

fun fromJava(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to fromJava(it.value) })
    is List<*> -> JsonArray(v.map { fromJava(it) })
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}
