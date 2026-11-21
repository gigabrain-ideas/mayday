package mayday.contract

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.adapters.llm.LlmProviders
import mayday.adapters.llm.RetryPolicy
import mayday.agents.AuthStyle
import mayday.agents.BudgetExhausted
import mayday.agents.CredentialProvider
import mayday.agents.GatewayAuth
import mayday.agents.GatewayError
import mayday.agents.LlmApi
import mayday.agents.LlmBlock
import mayday.agents.LlmConfig
import mayday.agents.LlmMessage
import mayday.agents.LlmProvider
import mayday.agents.LlmRequest
import mayday.agents.LlmRole
import mayday.agents.StopReason
import mayday.agents.ToolSpec
import mayday.shared.Telemetry
import mayday.suspendTest
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

/** An AI gateway in process. It records each request and answers with the queued replies. */
class FakeGateway : AutoCloseable {
    class Recorded(val path: String, val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String): List<String> = headers[name.lowercase()].orEmpty()
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
    }

    class Reply(val status: Int, val body: String = "{}", val headers: Map<String, String> = emptyMap())

    val requests = CopyOnWriteArrayList<Recorded>()
    private val replies = ConcurrentLinkedQueue<Reply>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.toList() }
            requests += Recorded(ex.requestURI.path, headers, ex.requestBody.readAllBytes().toString(Charsets.UTF_8))
            val reply = replies.poll() ?: Reply(500, """{"error":{"message":"no reply queued"}}""")
            reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = reply.body.toByteArray()
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val base = "http://127.0.0.1:${server.address.port}"

    fun reply(vararg r: Reply) = apply { replies.addAll(r) }
    override fun close() = server.stop(0)
}

/** A rotating short-lived token: each invalidate issues the next generation. */
class RotatingCredential : CredentialProvider {
    @Volatile var generation = 1
    @Volatile var invalidations = 0
    override suspend fun token() = "token-gen-$generation"
    override fun invalidate() {
        invalidations++
        generation++
    }
}

/** LLM provider contract: gateway identity, 401 refresh, 429 budget refusal, bounded retries, tool round trip, usage. */
abstract class LlmProviderContract {
    abstract val api: LlmApi
    abstract val prefix: String
    abstract val endpoint: String
    abstract fun text(text: String, input: Long = 11, output: Long = 7, model: String = "gw-model-1"): String
    abstract fun toolCall(id: String, name: String, argumentsJson: String): String
    abstract fun toolResult(body: JsonObject): Pair<String, String>?
    abstract fun toolNames(body: JsonObject): List<String>

    protected val gateway = FakeGateway()
    protected val credential = RotatingCredential()
    private val telemetry = object : Telemetry by Telemetry.noop {
        override fun propagationHeaders() = mapOf("traceparent" to TRACEPARENT)
    }

    @AfterTest
    fun stop() = gateway.close()

    protected fun provider(style: AuthStyle = AuthStyle.BEARER, baseUrl: String = gateway.base + prefix): LlmProvider =
        LlmProviders.withTelemetry(telemetry, RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(50))).create(
            LlmConfig(provider = api.id, model = "test-model", baseUrl = baseUrl),
            GatewayAuth(style, credential, mapOf("x-company-agent-id" to "agent-42", "x-company-tenant" to "sre")),
        )

    protected val ask = LlmRequest("You help.", listOf(LlmMessage(LlmRole.USER, listOf(LlmBlock.Text("Hello")))), emptyList())
    private fun ok(t: String = "Hi") = FakeGateway.Reply(200, text(t))

    @Test
    fun `api key style sends only x-api-key with the identity headers`() = suspendTest {
        gateway.reply(ok())
        provider(AuthStyle.API_KEY).complete(ask)
        val r = gateway.requests.single()
        assertEquals(prefix + endpoint, r.path)
        assertEquals(listOf("token-gen-1"), r.header("x-api-key"))
        assertEquals(emptyList(), r.header("authorization"))
        assertEquals(listOf("agent-42"), r.header("x-company-agent-id"))
        assertEquals(listOf("sre"), r.header("x-company-tenant"))
        assertEquals(listOf(TRACEPARENT), r.header("traceparent"))
    }

    @Test
    fun `bearer style sends only Authorization with the identity headers`() = suspendTest {
        gateway.reply(ok())
        provider(AuthStyle.BEARER).complete(ask)
        val r = gateway.requests.single()
        assertEquals(listOf("Bearer token-gen-1"), r.header("authorization"))
        assertEquals(emptyList(), r.header("x-api-key"))
        assertEquals(listOf("agent-42"), r.header("x-company-agent-id"))
        assertEquals(listOf(TRACEPARENT), r.header("traceparent"))
    }

    @Test
    fun `without a credential no auth header goes out`() = suspendTest {
        gateway.reply(ok())
        LlmProviders.withTelemetry(telemetry).create(
            LlmConfig(provider = api.id, model = "test-model", baseUrl = gateway.base + prefix),
            GatewayAuth(AuthStyle.API_KEY, null, mapOf("x-company-agent-id" to "agent-42")),
        ).complete(ask)
        val r = gateway.requests.single()
        assertEquals(emptyList(), r.header("authorization"))
        assertEquals(emptyList(), r.header("x-api-key"))
        assertEquals(listOf("agent-42"), r.header("x-company-agent-id"))
    }

    @Test
    fun `a rotated token goes out on the next call with the same provider`() = suspendTest {
        gateway.reply(ok(), ok())
        val p = provider()
        p.complete(ask)
        credential.generation = 5
        p.complete(ask)
        assertEquals(listOf("Bearer token-gen-1", "Bearer token-gen-5"), gateway.requests.map { it.header("authorization").single() })
    }

    @Test
    fun `401 refreshes the token once and retries once`() = suspendTest {
        gateway.reply(FakeGateway.Reply(401, """{"error":{"message":"expired"}}"""), ok("after refresh"))
        val resp = provider().complete(ask)
        assertEquals("after refresh", (resp.message.blocks.single() as LlmBlock.Text).text)
        assertEquals(1, credential.invalidations)
        assertEquals(listOf("Bearer token-gen-1", "Bearer token-gen-2"), gateway.requests.map { it.header("authorization").single() })
    }

    @Test
    fun `a second 401 fails without a loop`() = suspendTest {
        gateway.reply(FakeGateway.Reply(401), FakeGateway.Reply(401), ok())
        val e = assertFailsWith<GatewayError> { provider().complete(ask) }
        assertEquals(401, e.status)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun `429 throws BudgetExhausted and only one request reaches the gateway`() = suspendTest {
        gateway.reply(FakeGateway.Reply(429, """{"error":{"message":"budget used up"}}""", mapOf("Retry-After" to "7")), ok())
        val e = assertFailsWith<BudgetExhausted> { provider().complete(ask) }
        assertEquals(Duration.ofSeconds(7), e.retryAfter)
        assertEquals(1, gateway.requests.size)
        assertEquals(0, credential.invalidations)
    }

    @Test
    fun `429 reads the cooldown from retry-after-ms, rate limit reset headers and the JSON body`() = suspendTest {
        val cases = listOf(
            mapOf("retry-after-ms" to "2500") to "" to Duration.ofMillis(2500),
            mapOf("x-ratelimit-reset-requests" to "1s", "x-ratelimit-reset-tokens" to "6m0s") to "" to Duration.ofMinutes(6),
            emptyMap<String, String>() to """{"error":{"type":"rate_limit","retry_after":90}}""" to Duration.ofSeconds(90),
        )
        for ((input, expected) in cases) {
            val (headers, body) = input
            gateway.reply(FakeGateway.Reply(429, body, headers))
            assertEquals(expected, assertFailsWith<BudgetExhausted> { provider().complete(ask) }.retryAfter, "$headers $body")
        }
    }

    @Test
    fun `429 without a stated cooldown has no retryAfter, so the agent applies its default`() = suspendTest {
        gateway.reply(FakeGateway.Reply(429, """{"error":{"message":"retry in 5 seconds"}}"""), ok())
        assertNull(assertFailsWith<BudgetExhausted> { provider().complete(ask) }.retryAfter, "message text is never read")
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `503 is retried at most twice then fails`() = suspendTest {
        gateway.reply(FakeGateway.Reply(503), FakeGateway.Reply(503), FakeGateway.Reply(503), ok())
        val e = assertFailsWith<GatewayError> { provider().complete(ask) }
        assertEquals(503, e.status)
        assertEquals(3, gateway.requests.size)
    }

    @Test
    fun `a transient 502 recovers on retry with fresh headers`() = suspendTest {
        gateway.reply(FakeGateway.Reply(502), ok("recovered"))
        val resp = provider().complete(ask)
        assertEquals(StopReason.END, resp.stopReason)
        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.requests.all { it.header("authorization") == listOf("Bearer token-gen-1") })
    }

    @Test
    fun `503 with a long Retry-After is not retried`() = suspendTest {
        gateway.reply(FakeGateway.Reply(503, "{}", mapOf("Retry-After" to "120")), ok())
        assertFailsWith<GatewayError> { provider().complete(ask) }
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `other errors fail at once and the message carries no secret`() = suspendTest {
        gateway.reply(FakeGateway.Reply(400, """{"error":{"message":"rejected token-gen-1 for agent"}}"""), ok())
        val e = assertFailsWith<GatewayError> { provider(AuthStyle.API_KEY).complete(ask) }
        assertEquals(400, e.status)
        assertFalse(e.message!!.contains("token-gen-1"))
        assertTrue(e.message!!.contains("rejected"))
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `an unreachable gateway fails with status 0`() = suspendTest {
        val port = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<GatewayError> { provider(baseUrl = "http://127.0.0.1:$port$prefix").complete(ask) }
        assertEquals(0, e.status)
    }

    @Test
    fun `usage and model are mapped`() = suspendTest {
        gateway.reply(FakeGateway.Reply(200, text("Hi", input = 120, output = 33, model = "gw-model-2")))
        val resp = provider().complete(ask)
        assertEquals(120, resp.usage.inputTokens)
        assertEquals(33, resp.usage.outputTokens)
        assertEquals("gw-model-2", resp.model)
    }

    @Test
    fun `a base URL with a trailing slash keeps its path prefix`() = suspendTest {
        gateway.reply(ok())
        provider(baseUrl = gateway.base + prefix + "/").complete(ask)
        assertEquals(prefix + endpoint, gateway.requests.single().path)
    }

    @Test
    fun `tool use round trip`() = suspendTest {
        gateway.reply(FakeGateway.Reply(200, toolCall("call_1", "get_incident", """{"key":"INC-7"}""")), ok("INC-7 is resolved"))
        val tool = ToolSpec("get_incident", "Reads an incident", Json.parseToJsonElement("""{"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""").jsonObject)
        val p = provider()
        val first = p.complete(ask.copy(tools = listOf(tool)))
        assertEquals(StopReason.TOOL_USE, first.stopReason)
        val use = first.message.blocks.filterIsInstance<LlmBlock.ToolUse>().single()
        assertEquals("call_1", use.id)
        assertEquals("get_incident", use.name)
        assertEquals("INC-7", use.input["key"]!!.jsonPrimitive.content)
        assertEquals(listOf("get_incident"), toolNames(gateway.requests[0].json))

        val history = ask.messages + first.message + LlmMessage(LlmRole.USER, listOf(LlmBlock.ToolResult("call_1", "resolved at 10:02", false)))
        val second = p.complete(ask.copy(messages = history, tools = listOf(tool)))
        assertEquals(StopReason.END, second.stopReason)
        assertEquals("INC-7 is resolved", (second.message.blocks.single() as LlmBlock.Text).text)
        assertEquals("call_1" to "resolved at 10:02", toolResult(gateway.requests[1].json))
    }
}

class AnthropicProviderContractTest : LlmProviderContract() {
    override val api = LlmApi.ANTHROPIC
    override val prefix = "/gw/anthropic"
    override val endpoint = "/v1/messages"

    override fun text(text: String, input: Long, output: Long, model: String) =
        """{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":[{"type":"text","text":"$text"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":$input,"output_tokens":$output}}"""

    override fun toolCall(id: String, name: String, argumentsJson: String) =
        """{"id":"msg_2","type":"message","role":"assistant","model":"gw-model-1","content":[{"type":"text","text":"Reading."},{"type":"tool_use","id":"$id","name":"$name","input":$argumentsJson}],"stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":5,"output_tokens":5}}"""

    override fun toolResult(body: JsonObject): Pair<String, String>? {
        val messages = body["messages"]!!.jsonArray
        assertEquals("tool_use", messages[1].jsonObject["content"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
        val block = messages.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        if (block["type"]?.jsonPrimitive?.content != "tool_result") return null
        val content = block["content"]!!.let { c -> (c as? JsonPrimitive)?.content ?: c.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content }
        return block["tool_use_id"]!!.jsonPrimitive.content to content
    }

    override fun toolNames(body: JsonObject) = body["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
}

class OpenAiProviderContractTest : LlmProviderContract() {
    override val api = LlmApi.OPENAI
    override val prefix = "/gw/openai/v1"
    override val endpoint = "/chat/completions"

    override fun text(text: String, input: Long, output: Long, model: String) =
        """{"id":"c1","object":"chat.completion","model":"$model","choices":[{"index":0,"message":{"role":"assistant","content":"$text","refusal":null},"finish_reason":"stop"}],"usage":{"prompt_tokens":$input,"completion_tokens":$output,"total_tokens":${input + output}}}"""

    override fun toolCall(id: String, name: String, argumentsJson: String) =
        """{"id":"c2","object":"chat.completion","model":"gw-model-1","choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(argumentsJson))}}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":5}}"""

    override fun toolResult(body: JsonObject): Pair<String, String>? {
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("system", messages.first()["role"]!!.jsonPrimitive.content)
        val call = messages.first { it["role"]!!.jsonPrimitive.content == "assistant" }["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        val tool = messages.lastOrNull { it["role"]!!.jsonPrimitive.content == "tool" } ?: return null
        return tool["tool_call_id"]!!.jsonPrimitive.content to tool["content"]!!.jsonPrimitive.content
    }

    override fun toolNames(body: JsonObject) = body["tools"]!!.jsonArray.map {
        assertEquals("function", it.jsonObject["type"]!!.jsonPrimitive.content)
        it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
    }

    @Test
    fun `the request sets max_completion_tokens and no reasoning parameter`() = suspendTest {
        gateway.reply(FakeGateway.Reply(200, text("Hi")))
        provider().complete(ask)
        val body = gateway.requests.single().json
        assertEquals(16000, body["max_completion_tokens"]!!.jsonPrimitive.content.toLong())
        assertNull(body["reasoning_effort"])
        assertNull(body["reasoning"])
        assertNull(body["tools"])
    }

    @Test
    fun `invalid tool arguments become an empty object and finish reasons map`() = suspendTest {
        gateway.reply(
            FakeGateway.Reply(200, toolCall("c9", "get_incident", "{not json")),
            FakeGateway.Reply(200, text("x").replace("\"stop\"", "\"length\"")),
            FakeGateway.Reply(200, text("x").replace("\"stop\"", "\"content_filter\"")),
        )
        val p = provider()
        val use = p.complete(ask).message.blocks.filterIsInstance<LlmBlock.ToolUse>().single()
        assertEquals(JsonObject(emptyMap()), use.input)
        assertEquals(StopReason.MAX_TOKENS, p.complete(ask).stopReason)
        assertEquals(StopReason.REFUSAL, p.complete(ask).stopReason)
    }

    @Test
    fun `the raw assistant message is replayed with its exact arguments`() = suspendTest {
        gateway.reply(FakeGateway.Reply(200, toolCall("c3", "get_incident", """{"key": "INC-1"}""")), FakeGateway.Reply(200, text("done")))
        val p = provider()
        val first = p.complete(ask)
        p.complete(ask.copy(messages = ask.messages + first.message + LlmMessage(LlmRole.USER, listOf(LlmBlock.ToolResult("c3", "ok", true)))))
        val messages = gateway.requests[1].json["messages"]!!.jsonArray.map { it.jsonObject }
        val args = messages[2]["tool_calls"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertEquals("""{"key": "INC-1"}""", args)
        assertEquals("Error: ok", messages[3]["content"]!!.jsonPrimitive.content)
        assertTrue(messages.none { (it["content"] as? JsonArray) != null })
    }
}
