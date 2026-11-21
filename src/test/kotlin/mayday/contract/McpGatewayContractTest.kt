package mayday.contract

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mayday.adapters.mcp.McpGateway
import mayday.agents.ToolOutcome
import mayday.agents.AuthStyle
import mayday.agents.CredentialProvider
import mayday.agents.GatewayAuth
import mayday.agents.McpServerConfig
import mayday.agents.McpServerRepository
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.shared.Actor
import mayday.shared.Clock
import mayday.shared.JSON
import mayday.shared.SecretBox
import mayday.shared.SpanKind
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import mayday.suspendTest
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract of the tool gateway against a remote MCP server (company tools gateway) over Streamable HTTP. */
class McpGatewayContractTest {
    private class Req(val method: String, val headers: Map<String, String>, val tool: String?)
    private class Reply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: JsonObject? = null)

    /** In-process MCP server. [override] answers instead of the default behaviour when it returns a reply. */
    private class FakeMcp(val tools: List<String>, val sse: Boolean = false) {
        val requests = CopyOnWriteArrayList<Req>()
        val sessions: MutableSet<String> = ConcurrentHashMap.newKeySet()
        @Volatile var override: ((Req) -> Reply?)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}/mcp"

        init {
            server.createContext("/mcp") { ex ->
                val msg = JSON.parseToJsonElement(ex.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
                val method = msg["method"]!!.jsonPrimitive.content
                val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
                val req = Req(method, headers, (msg["params"] as? JsonObject)?.get("name")?.jsonPrimitive?.content)
                requests += req
                val reply = override?.invoke(req) ?: answer(req, msg)
                reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                val body = reply.body?.let { b ->
                    val full = buildJsonObject { put("jsonrpc", "2.0"); put("id", msg["id"]!!); b.forEach { (k, v) -> put(k, v) } }
                    val text = JSON.encodeToString(JsonObject.serializer(), full)
                    if (sse) { ex.responseHeaders.add("Content-Type", "text/event-stream"); "event: message\ndata: $text\n\n" }
                    else { ex.responseHeaders.add("Content-Type", "application/json"); text }
                }
                if (body == null) ex.sendResponseHeaders(reply.status, -1)
                else body.toByteArray().let { ex.sendResponseHeaders(reply.status, it.size.toLong()); ex.responseBody.write(it) }
                ex.close()
            }
            server.start()
        }

        private fun answer(req: Req, msg: JsonObject): Reply {
            if (req.method == "initialize") {
                val sid = UUID.randomUUID().toString().also { sessions += it }
                return Reply(200, mapOf("Mcp-Session-Id" to sid), buildJsonObject { putJsonObject("result") { put("protocolVersion", "2025-11-25") } })
            }
            if (req.headers["mcp-session-id"] !in sessions) return Reply(404)
            return when (req.method) {
                "notifications/initialized" -> Reply(202)
                "tools/list" -> Reply(200, body = buildJsonObject {
                    putJsonObject("result") {
                        putJsonArray("tools") { tools.forEach { t -> add(buildJsonObject { put("name", t); put("description", "Tool $t"); putJsonObject("inputSchema") { put("type", "object") } }) } }
                    }
                })
                "tools/call" -> Reply(200, body = buildJsonObject {
                    putJsonObject("result") { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "called ${req.tool}") }) }); put("isError", false) }
                })
                else -> Reply(200, body = buildJsonObject { putJsonObject("error") { put("code", -32601); put("message", "no ${msg["method"]}") } })
            }
        }

        fun count(method: String) = requests.count { it.method == method }
        fun stop() = server.stop(0)
    }

    private class Repo(vararg servers: McpServerConfig) : McpServerRepository {
        val all = servers.associateBy { it.id }.toMutableMap()
        override suspend fun get(id: String) = all[id]
        override suspend fun list() = all.values.toList()
        override suspend fun save(server: McpServerConfig) { all[server.id] = server }
        override suspend fun delete(id: String) { all.remove(id) }
    }

    private class RotatingToken : CredentialProvider {
        val issued = AtomicInteger()
        val invalidations = AtomicInteger()
        override suspend fun token() = "t${issued.incrementAndGet()}"
        override fun invalidate() { invalidations.incrementAndGet() }
    }

    private class Span(val name: String, val attributes: MutableMap<String, Any?>) {
        var failed: String? = null
    }

    private class RecordingTelemetry : Telemetry {
        val spans = CopyOnWriteArrayList<Span>()
        override suspend fun <T> span(name: String, attributes: Map<String, Any?>, kind: SpanKind, block: suspend (SpanScope) -> T): T {
            val s = Span(name, attributes.toMutableMap()).also { spans += it }
            return block(object : SpanScope {
                override val traceId = "0af7651916cd43dd8448eb211c80319c"
                override fun set(key: String, value: Any?) { s.attributes[key] = value }
                override fun event(name: String, attributes: Map<String, Any?>) = Unit
                override fun fail(message: String, error: Throwable?) { s.failed = message }
            })
        }
        override fun propagationHeaders() = mapOf("traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-11-20T10:00:00Z")) : Clock {
        override fun now() = now
        fun advance(d: Duration) { now = now.plus(d) }
    }

    private val box = SecretBox("test-master-key".toByteArray())
    private val principal = Principal(Actor.system, null, OrgRole.ADMIN, emptySet())
    private val fakes = mutableListOf<FakeMcp>()

    @AfterTest
    fun stop() = fakes.forEach { it.stop() }

    private fun fake(vararg tools: String, sse: Boolean = false) = FakeMcp(tools.toList(), sse).also { fakes += it }

    private fun server(fake: FakeMcp, name: String = "Tools", agent: Boolean = true, id: String = name) =
        McpServerConfig(id, name, fake.url, useAgentIdentity = agent, createdAt = Instant.EPOCH)

    private val token = RotatingToken()
    private val identityCalls = AtomicInteger()
    @Volatile private var agentId = "agent-7"
    private val identity: suspend () -> GatewayAuth = {
        identityCalls.incrementAndGet()
        GatewayAuth(AuthStyle.BEARER, token, mapOf("x-company-agent-id" to agentId, "x-tenant" to "acme"))
    }

    private fun gateway(vararg servers: McpServerConfig, telemetry: Telemetry = Telemetry.noop, clock: Clock = Clock.system) =
        McpGateway(Repo(*servers), box, identity, telemetry, clock)

    @Test
    fun `identity mode sends fresh identity headers and trace context on every request`() = suspendTest {
        val f = fake("query", sse = true)
        val gw = gateway(server(f), telemetry = RecordingTelemetry())
        assertEquals(listOf("tools__query"), gw.tools(principal, true).map { it.name })
        assertEquals("called query", gw.call(principal, "tools__query", JsonObject(emptyMap()), true).content)

        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), f.requests.map { it.method })
        val tokens = f.requests.map { it.headers["authorization"] }
        assertEquals(tokens.size, tokens.toSet().size, "a new token value per request: $tokens")
        f.requests.forEach { r ->
            assertTrue(r.headers["authorization"]!!.matches(Regex("Bearer t\\d+")))
            assertEquals("agent-7", r.headers["x-company-agent-id"])
            assertEquals("acme", r.headers["x-tenant"])
            assertTrue(r.headers["traceparent"]!!.startsWith("00-0af7651916cd43dd8448eb211c80319c-"))
        }
        assertTrue(identityCalls.get() > 0)
    }

    @Test
    fun `static credential mode sends the sealed credential in the configured header`() = suspendTest {
        val f = fake("query")
        val s = server(f, agent = false).copy(headerName = "X-Api-Key", credentialSealed = box.seal("s3cret"))
        val gw = gateway(s)
        assertEquals(1, gw.tools(principal, true).size)
        f.requests.forEach { r ->
            assertEquals("s3cret", r.headers["x-api-key"])
            assertEquals(null, r.headers["authorization"])
            assertEquals(null, r.headers["x-company-agent-id"])
        }
        assertEquals(0, identityCalls.get(), "identity is not used")
    }

    @Test
    fun `allowlist filters tools and names get the server prefix`() = suspendTest {
        val f = fake("query", "delete_all")
        val gw = gateway(server(f, name = "Grafana Prod", agent = false).copy(toolAllowlist = listOf("query")))
        val tools = gw.tools(principal, true)
        assertEquals(listOf("grafana_prod__query"), tools.map { it.name })
        assertEquals("[Grafana Prod] Tool query", tools.single().description)
        assertEquals("called query", gw.call(principal, "grafana_prod__query", JsonObject(emptyMap()), true).content)
        assertTrue(gw.call(principal, "grafana_prod__delete_all", JsonObject(emptyMap()), true).isError)
        assertEquals(0, f.requests.count { it.tool == "delete_all" })
        assertTrue(gw.tools(principal, false).isEmpty(), "external agents never see gateway tools")
    }

    @Test
    fun `HTTP 401 invalidates the token and retries once`() = suspendTest {
        val f = fake("query")
        val refused = AtomicInteger()
        f.override = { r -> if (r.method == "tools/list" && refused.getAndIncrement() == 0) Reply(401) else null }
        val gw = gateway(server(f))
        assertEquals(1, gw.tools(principal, true).size)
        assertEquals(1, token.invalidations.get())
        assertEquals(2, f.count("tools/list"))
    }

    @Test
    fun `persistent HTTP 401 retries only once and reports unauthorized`() = suspendTest {
        val f = fake("query")
        val tel = RecordingTelemetry()
        val gw = gateway(server(f), telemetry = tel)
        gw.tools(principal, true)
        f.override = { r -> if (r.method == "tools/call") Reply(401) else null }
        val out = gw.call(principal, "tools__query", JsonObject(emptyMap()), true)
        assertTrue(out.isError)
        assertEquals(2, f.count("tools/call"))
        assertEquals("unauthorized", tel.spans.last().attributes["error.type"])
    }

    @Test
    fun `a tool without a grant answers isError and its message reaches the model unchanged`() = suspendTest {
        val f = fake("query", "restart")
        val tel = RecordingTelemetry()
        val gw = gateway(server(f), telemetry = tel)
        gw.tools(principal, true)
        val denial = "Grant missing: agent agt-7 may not call restart (policy ops-write)"
        f.override = { r ->
            if (r.tool == "restart") Reply(200, body = buildJsonObject {
                putJsonObject("result") { put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", denial) }) }); put("isError", true) }
            }) else null
        }
        val before = f.requests.size
        val out = gw.call(principal, "tools__restart", JsonObject(emptyMap()), true)
        assertEquals(ToolOutcome(denial, true), out)
        assertEquals(before + 1, f.requests.size, "one call, no retry")
        assertEquals("tool_error", tel.spans.single { it.name == "mcp tools/call restart" }.attributes["error.type"])
        assertEquals(setOf("tools__query", "tools__restart"), gw.tools(principal, true).map { it.name }.toSet(), "the gateway decides each call; Mayday hides nothing")
        assertEquals("called query", gw.call(principal, "tools__query", JsonObject(emptyMap()), true).content, "the run continues")
    }

    @Test
    fun `transport and JSON-RPC errors are tool errors whatever their message says`() = suspendTest {
        val f = fake("restart")
        val gw = gateway(server(f))
        gw.tools(principal, true)
        f.override = { r -> if (r.tool == "restart") Reply(403) else null }
        assertTrue(gw.call(principal, "tools__restart", JsonObject(emptyMap()), true).isError)
        f.override = { r ->
            if (r.tool == "restart") Reply(200, body = buildJsonObject { putJsonObject("error") { put("code", -32003); put("message", "Tool 'restart' is not permitted for this identity") } }) else null
        }
        assertTrue(gw.call(principal, "tools__restart", JsonObject(emptyMap()), true).isError)
        assertEquals(listOf("tools__restart"), gw.tools(principal, true).map { it.name }, "no message decides to hide a tool")
    }

    @Test
    fun `HTTP 429 pauses the server without retry until Retry-After`() = suspendTest {
        val f = fake("query")
        val clock = MutableClock()
        val tel = RecordingTelemetry()
        val gw = gateway(server(f), telemetry = tel, clock = clock)
        gw.tools(principal, true)
        f.override = { r -> if (r.method == "tools/call") Reply(429, mapOf("Retry-After" to "300")) else null }

        val first = gw.call(principal, "tools__query", JsonObject(emptyMap()), true)
        assertTrue(first.isError && "HTTP 429" in first.content)
        assertEquals(1, f.count("tools/call"))
        assertEquals("budget_exhausted", tel.spans.last().attributes["error.type"])

        val before = f.requests.size
        clock.advance(Duration.ofSeconds(200))
        assertTrue(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
        assertEquals(listOf("tools__query"), gw.tools(principal, true).map { it.name }, "cached tools stay during the pause")
        assertEquals(before, f.requests.size, "no network I/O during the pause")

        f.override = null
        clock.advance(Duration.ofSeconds(101))
        assertEquals("called query", gw.call(principal, "tools__query", JsonObject(emptyMap()), true).content)
    }

    @Test
    fun `HTTP 429 without a stated cooldown pauses for 15 minutes`() = suspendTest {
        val f = fake("query")
        val clock = MutableClock()
        val gw = gateway(server(f), clock = clock)
        gw.tools(principal, true)
        f.override = { r -> if (r.method == "tools/call") Reply(429) else null }
        assertTrue(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
        f.override = null
        clock.advance(Duration.ofMinutes(14))
        assertTrue(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
        assertEquals(1, f.count("tools/call"))
        clock.advance(Duration.ofMinutes(1).plusSeconds(1))
        assertFalse(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
    }

    @Test
    fun `HTTP 429 uses the cooldown of the RateLimit header`() = suspendTest {
        val f = fake("query")
        val clock = MutableClock()
        val gw = gateway(server(f), clock = clock)
        gw.tools(principal, true)
        f.override = { r -> if (r.method == "tools/call") Reply(429, mapOf("RateLimit" to "\"budget\";r=0;t=120")) else null }
        assertTrue(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
        f.override = null
        clock.advance(Duration.ofSeconds(119))
        assertTrue(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
        assertEquals(1, f.count("tools/call"))
        clock.advance(Duration.ofSeconds(2))
        assertFalse(gw.call(principal, "tools__query", JsonObject(emptyMap()), true).isError)
    }

    @Test
    fun `a broken server does not hide the tools of another server`() = suspendTest {
        val good = fake("query")
        val bad = fake("x").also { it.override = { Reply(500) } }
        val tel = RecordingTelemetry()
        val gw = gateway(server(bad, name = "Broken"), server(good, name = "Good"), telemetry = tel)
        assertEquals(listOf("good__query"), gw.tools(principal, true).map { it.name })
        val failed = tel.spans.single { it.name == "mcp tools/list Broken" }
        assertEquals("transport", failed.attributes["error.type"])
        assertEquals(500, failed.attributes["http.response.status_code"])
    }

    @Test
    fun `an expired session is initialized again`() = suspendTest {
        val f = fake("query")
        val gw = gateway(server(f))
        gw.tools(principal, true)
        f.sessions.clear()
        assertEquals("called query", gw.call(principal, "tools__query", JsonObject(emptyMap()), true).content)
        assertEquals(2, f.count("initialize"))
    }

    @Test
    fun `tool list is cached for two minutes and a new identity invalidates the cache`() = suspendTest {
        val f = fake("query")
        val clock = MutableClock()
        val tel = RecordingTelemetry()
        val gw = gateway(server(f), telemetry = tel, clock = clock)
        gw.tools(principal, true)
        gw.tools(principal, true)
        assertEquals(1, f.count("tools/list"))
        val span = tel.spans.single { it.name == "mcp tools/list Tools" }
        assertEquals("tools/list", span.attributes["mcp.method.name"])
        assertEquals("127.0.0.1", span.attributes["server.address"])
        assertEquals(f.sessions.single(), span.attributes["mcp.session.id"])

        agentId = "agent-8"
        gw.tools(principal, true)
        assertEquals(2, f.count("tools/list"))
        assertEquals("agent-8", f.requests.last().headers["x-company-agent-id"])

        clock.advance(Duration.ofSeconds(121))
        gw.tools(principal, true)
        assertEquals(3, f.count("tools/list"))
    }
}
