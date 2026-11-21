package mayday.adapters.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mayday.agents.ToolOutcome
import mayday.shared.JSON
import mayday.shared.SpanScope
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** The MCP server answered with a non-2xx HTTP status. [cooldown] is the wait that the response states, if any. */
class McpHttpException(val status: Int, val cooldown: java.time.Duration? = null) : RuntimeException("MCP server answered HTTP $status")

/** The MCP server answered with a JSON-RPC error. */
class McpRpcException(val code: Int, message: String) : RuntimeException("MCP error: $message")

private class SessionExpired : RuntimeException("session expired")

/**
 * Minimal MCP client (Streamable HTTP transport) for one remote server.
 * [headers] runs before every HTTP request, so rotating tokens are always fresh.
 * [onUnauthorized], when set, runs after HTTP 401 and the request is sent once more.
 */
class McpClient(
    private val url: String,
    private val http: HttpClient,
    private val headers: suspend () -> Map<String, String>,
    private val onUnauthorized: (() -> Unit)? = null,
) {
    private class Session(val id: String)

    private val uri = URI(url)
    private val ids = AtomicLong()
    private val session = AtomicReference<Session?>(null)
    private val initLock = Mutex()

    val sessionId: String? get() = session.get()?.id?.ifEmpty { null }

    private suspend fun exchange(body: JsonObject, sessionId: String?, span: SpanScope?): HttpResponse<String> {
        val payload = JSON.encodeToString(JsonObject.serializer(), body)
        suspend fun send(): HttpResponse<String> {
            val b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpServer.PROTOCOL)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
            headers().forEach { (k, v) -> if (k.isNotBlank() && v.isNotEmpty()) b.setHeader(k, v) }
            if (!sessionId.isNullOrEmpty()) b.header("Mcp-Session-Id", sessionId)
            val req = b.build()
            return withContext(Dispatchers.IO) { http.send(req, HttpResponse.BodyHandlers.ofString()) }
        }
        var resp = send()
        if (resp.statusCode() == 401 && onUnauthorized != null) {
            onUnauthorized.invoke()
            resp = send()
        }
        span?.set("http.response.status_code", resp.statusCode())
        return resp
    }

    private fun check(resp: HttpResponse<String>, sessionId: String?, current: Session?) {
        val status = resp.statusCode()
        if (status == 404 && !sessionId.isNullOrEmpty()) {
            session.compareAndSet(current, null)
            throw SessionExpired()
        }
        if (status !in 200..299) throw McpHttpException(status, if (status == 429) mayday.agents.statedCooldown(resp.headers()::allValues, resp.body()) else null)
    }

    private fun result(resp: HttpResponse<String>, id: JsonElement?): JsonObject {
        val text = resp.body()
        val candidates = if (resp.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) sseData(text) else listOf(text)
        val msg = candidates.firstNotNullOfOrNull { c ->
            runCatching { JSON.parseToJsonElement(c).jsonObject }.getOrNull()?.takeIf { it["id"] == id }
        } ?: throw IllegalStateException("No response from MCP server")
        (msg["error"] as? JsonObject)?.let { e ->
            throw McpRpcException((e["code"] as? JsonPrimitive)?.intOrNull ?: 0, (e["message"] as? JsonPrimitive)?.content.orEmpty())
        }
        return msg["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun rpc(method: String, params: JsonObject) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", ids.incrementAndGet()); put("method", method); put("params", params) }

    private suspend fun ensureSession(span: SpanScope?): Session {
        session.get()?.let { return it }
        return initLock.withLock {
            session.get()?.let { return@withLock it }
            val init = rpc("initialize", buildJsonObject {
                put("protocolVersion", McpServer.PROTOCOL)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "mayday"); put("version", "0.1.0") }
            })
            val resp = exchange(init, null, span)
            check(resp, null, null)
            result(resp, init["id"])
            val sid = resp.headers().firstValue("Mcp-Session-Id").orElse("")
            val note = exchange(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, sid, span)
            check(note, null, null)
            Session(sid).also { session.set(it) }
        }
    }

    private suspend fun request(method: String, params: JsonObject, span: SpanScope?): JsonObject {
        suspend fun once(): JsonObject {
            val s = ensureSession(span)
            if (s.id.isNotEmpty()) span?.set("mcp.session.id", s.id)
            val body = rpc(method, params)
            val resp = exchange(body, s.id, span)
            check(resp, s.id, s)
            return result(resp, body["id"])
        }
        return try { once() } catch (_: SessionExpired) { once() }
    }

    suspend fun listTools(span: SpanScope? = null): List<JsonObject> =
        (request("tools/list", JsonObject(emptyMap()), span)["tools"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    suspend fun callTool(name: String, args: JsonObject, span: SpanScope? = null): ToolOutcome {
        val r = request("tools/call", buildJsonObject { put("name", name); put("arguments", args) }, span)
        val text = (r["content"] as? JsonArray)?.joinToString("\n") { c ->
            val o = c.jsonObject
            o["text"]?.jsonPrimitive?.content ?: JSON.encodeToString(JsonElement.serializer(), o)
        }.orEmpty()
        return ToolOutcome(text, (r["isError"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    companion object {
        /** The data payloads of a text/event-stream body, one per event. */
        fun sseData(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var has = false
            for (line in text.lineSequence()) {
                if (line.isEmpty()) {
                    if (has) out += cur.toString()
                    cur.setLength(0); has = false
                } else if (line.startsWith("data:")) {
                    if (has) cur.append('\n')
                    cur.append(line.removePrefix("data:").removePrefix(" "))
                    has = true
                }
            }
            if (has) out += cur.toString()
            return out
        }
    }
}
