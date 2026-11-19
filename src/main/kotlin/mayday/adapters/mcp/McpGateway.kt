package mayday.adapters.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mayday.agents.McpServerConfig
import mayday.agents.McpServerRepository
import mayday.agents.ToolCatalog
import mayday.agents.ToolOutcome
import mayday.agents.ToolSpec
import mayday.directory.slugify
import mayday.identity.Principal
import mayday.shared.JSON
import mayday.shared.SecretBox
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Minimal MCP client (Streamable HTTP transport) for one remote server. */
class McpClient(private val url: String, private val headerName: String, private val credential: String, private val http: HttpClient) {
    private val ids = AtomicLong()
    @Volatile private var session: String? = null

    private fun post(body: JsonObject, expectResult: Boolean): JsonObject? {
        val b = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpServer.PROTOCOL)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.encodeToString(JsonObject.serializer(), body)))
        if (credential.isNotEmpty()) b.header(headerName, credential)
        session?.let { b.header("Mcp-Session-Id", it) }
        val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        resp.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
        if (resp.statusCode() == 404 && session != null) { session = null; throw IllegalStateException("session expired") }
        if (resp.statusCode() !in 200..299) throw IllegalStateException("MCP server answered HTTP ${resp.statusCode()}")
        if (!expectResult) return null
        val id = body["id"]
        val text = resp.body()
        val candidates = if (resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.toList()
        } else listOf(text)
        val msg = candidates.mapNotNull { runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull() }.firstOrNull { it["id"] == id }
            ?: throw IllegalStateException("No response from MCP server")
        msg["error"]?.let { throw IllegalStateException("MCP error: ${it.jsonObject["message"]?.jsonPrimitive?.content}") }
        return msg["result"]?.jsonObject
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", ids.incrementAndGet()); put("method", method); put("params", params) }
        return post(body, true)!!
    }

    @Synchronized
    private fun ensureSession() {
        if (session != null) return
        request("initialize", buildJsonObject {
            put("protocolVersion", McpServer.PROTOCOL)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "mayday"); put("version", "0.1.0") }
        })
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, false)
        if (session == null) session = ""
    }

    private fun <T> withSession(f: () -> T): T {
        ensureSession()
        return try { f() } catch (e: IllegalStateException) {
            if (e.message == "session expired") { ensureSession(); f() } else throw e
        }
    }

    fun listTools(): List<JsonObject> = withSession {
        (request("tools/list", JsonObject(emptyMap()))["tools"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
    }

    fun callTool(name: String, args: JsonObject): ToolOutcome = withSession {
        val r = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
        val text = (r["content"] as? JsonArray)?.joinToString("\n") { c ->
            val o = c.jsonObject
            o["text"]?.jsonPrimitive?.content ?: JSON.encodeToString(JsonElement.serializer(), o)
        }.orEmpty()
        ToolOutcome(text, (r["isError"] as? JsonPrimitive)?.booleanOrNull == true)
    }
}

/** Tool gateway: exposes the tools of the configured MCP servers to the built-in agent only. */
class McpGateway(private val servers: McpServerRepository, private val box: SecretBox) : ToolCatalog {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private data class Cached(val at: Instant, val server: McpServerConfig, val client: McpClient, val tools: List<ToolSpec>, val names: Map<String, String>)
    private val cache = ConcurrentHashMap<String, Cached>()

    private fun load(s: McpServerConfig): Cached? {
        val c = cache[s.id]
        if (c != null && c.server == s && c.at.isAfter(Instant.now().minusSeconds(120))) return c
        return runCatching {
            val client = c?.takeIf { it.server == s }?.client ?: McpClient(s.url, s.headerName, box.open(s.credentialSealed), http)
            val prefix = slugify(s.name).replace('-', '_')
            val names = HashMap<String, String>()
            val tools = client.listTools().mapNotNull { t ->
                val name = t["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (s.toolAllowlist.isNotEmpty() && name !in s.toolAllowlist) return@mapNotNull null
                val exposed = "${prefix}__$name".take(64)
                names[exposed] = name
                ToolSpec(exposed, "[${s.name}] " + (t["description"]?.jsonPrimitive?.content ?: name),
                    (t["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") })
            }
            Cached(Instant.now(), s, client, tools, names).also { cache[s.id] = it }
        }.getOrNull()
    }

    override fun tools(principal: Principal, internal: Boolean): List<ToolSpec> {
        if (!internal) return emptyList()
        return servers.list().filter { it.enabled }.mapNotNull { load(it) }.flatMap { it.tools }
    }

    override fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome {
        if (!internal) return ToolOutcome("Unknown tool '$name'", true)
        for (s in servers.list().filter { it.enabled }) {
            val c = load(s) ?: continue
            val remote = c.names[name] ?: continue
            return runCatching { c.client.callTool(remote, args) }.getOrElse { ToolOutcome(it.message ?: "Tool failed", true) }
        }
        return ToolOutcome("Unknown tool '$name'", true)
    }
}
