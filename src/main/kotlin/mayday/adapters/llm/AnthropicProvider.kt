package mayday.adapters.llm

import com.anthropic.backends.Backend
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.opentelemetry.OpenTelemetryOptions
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import mayday.agents.GatewayAuth
import mayday.agents.LlmBlock
import mayday.agents.LlmConfig
import mayday.agents.LlmMessage
import mayday.agents.LlmProvider
import mayday.agents.LlmRequest
import mayday.agents.LlmResponse
import mayday.agents.LlmRole
import mayday.agents.LlmUsage
import mayday.agents.StopReason
import mayday.agents.statedCooldown
import mayday.shared.Telemetry
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Models that accept server-side refusal fallbacks. */
private val FALLBACK_MODELS = setOf("claude-fable-5-2", "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")

private const val ANTHROPIC_URL = "https://api.anthropic.com"

/**
 * SDK backend that adds no credential. The [GatewayAuth] headers go on each request instead,
 * so a rotated token needs no new client and exactly one auth header goes out.
 */
private class GatewayBackend(private val url: String) : Backend {
    override fun baseUrl() = url
    override fun close() = Unit
}

/** One SDK client per base URL. SDK tracing is off: InternalAgent opens the `chat` span and the provider propagates its context. */
internal object AnthropicClients {
    private val clients = ConcurrentHashMap<String, AnthropicClient>()

    fun forBaseUrl(baseUrl: String): AnthropicClient = clients.computeIfAbsent(baseUrl.trim().trimEnd('/').ifEmpty { ANTHROPIC_URL }) { url ->
        AnthropicOkHttpClient.builder()
            .backend(GatewayBackend(url))
            .timeout(Duration.ofMinutes(10))
            .maxRetries(0)
            .openTelemetry(OpenTelemetryOptions.builder().propagation(false).traces(false).build())
            .build()
    }
}

class AnthropicProvider internal constructor(
    private val client: AnthropicClient,
    private val config: LlmConfig,
    auth: GatewayAuth,
    telemetry: Telemetry,
    retry: RetryPolicy,
) : LlmProvider {
    private val caller = GatewayCaller(auth, telemetry, retry)

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val params = params(request)
        val resp = caller.call { headers ->
            val b = params.toBuilder()
            headers.forEach { (k, v) -> b.replaceAdditionalHeaders(k, v) }
            try {
                client.async().messages().create(b.build()).await()
            } catch (e: AnthropicServiceException) {
                val h = e.headers()
                val body = runCatching { com.anthropic.core.jsonMapper().writeValueAsString(e.body()) }.getOrDefault("")
                val cooldown = statedCooldown({ name -> h.names().filter { it.equals(name, true) }.flatMap { h.values(it) } }, body)
                throw HttpFailure(e.statusCode(), cooldown, errorDetail(body))
            } catch (e: AnthropicIoException) {
                throw ioFailure(e.cause ?: e)
            } catch (e: AnthropicRetryableException) {
                throw ioFailure(e.cause ?: e)
            } catch (e: IOException) {
                throw ioFailure(e)
            }
        }
        return toResponse(resp)
    }

    private fun params(request: LlmRequest): MessageCreateParams {
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
        return b.build()
    }

    private fun toResponse(resp: Message): LlmResponse {
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
        val u = resp.usage()
        val input = u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L)
        return LlmResponse(LlmMessage(LlmRole.ASSISTANT, blocks, resp), stop, LlmUsage(input, u.outputTokens()), resp.model().asString())
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
