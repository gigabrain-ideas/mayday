package mayday.adapters.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mayday.agents.AuthStyle
import mayday.agents.CredentialProvider
import mayday.agents.GatewayAuth
import mayday.agents.McpServerConfig
import mayday.agents.McpServerRepository
import mayday.agents.ToolCatalog
import mayday.agents.ToolOutcome
import mayday.agents.ToolSpec
import mayday.agents.DEFAULT_COOLDOWN
import mayday.directory.slugify
import mayday.identity.Principal
import mayday.shared.Clock
import mayday.shared.SecretBox
import mayday.shared.SpanKind
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Tool gateway: exposes the tools of the configured MCP servers to the built-in agent only.
 * With [McpServerConfig.useAgentIdentity] every request carries the agent identity headers from [identity].
 * The remote gateway grants tools per identity. A refused tool answers `isError: true` with a message; that result goes
 * to the model unchanged. Error messages are never parsed. HTTP 429 pauses the server.
 */
class McpGateway(
    private val servers: McpServerRepository,
    private val box: SecretBox,
    private val identity: suspend () -> GatewayAuth = { GatewayAuth.none },
    private val telemetry: Telemetry = Telemetry.noop,
    private val clock: Clock = Clock.system,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : ToolCatalog {
    /** What changes the headers apart from the token value. A change drops the cached tools and the session. */
    private data class IdentityKey(
        val agent: Boolean,
        val style: AuthStyle?,
        val headers: Map<String, String>,
        val credential: CredentialProvider?,
    )

    private class Cached(val at: Instant, val server: McpServerConfig, val key: IdentityKey, val client: McpClient, val tools: List<ToolSpec>, val names: Map<String, String>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val paused = ConcurrentHashMap<String, Instant>()
    private val failing = ConcurrentHashMap<String, String>()

    private suspend fun auth(s: McpServerConfig): Pair<IdentityKey, GatewayAuth?> {
        if (!s.useAgentIdentity) return IdentityKey(false, null, emptyMap(), null) to null
        val a = identity()
        return IdentityKey(true, a.style, a.headers, a.credential) to a
    }

    private fun client(s: McpServerConfig, auth: GatewayAuth?): McpClient {
        if (auth != null) {
            return McpClient(s.url, http, { auth.headers() + telemetry.propagationHeaders() }, { auth.invalidate() })
        }
        val credential = box.open(s.credentialSealed)
        val static = if (credential.isNotEmpty()) mapOf(s.headerName to credential) else emptyMap()
        return McpClient(s.url, http, { static + telemetry.propagationHeaders() })
    }

    private fun pausedUntil(s: McpServerConfig): Instant? {
        val until = paused[s.id] ?: return null
        if (clock.now().isBefore(until)) return until
        paused.remove(s.id, until)
        return null
    }

    private suspend fun load(s: McpServerConfig): Cached? {
        val (key, auth) = auth(s)
        val c = cache[s.id]?.takeIf { it.server == s && it.key == key }
        if (c != null && (pausedUntil(s) != null || c.at.isAfter(clock.now().minusSeconds(CACHE_SECONDS)))) return c
        if (pausedUntil(s) != null) return null
        return locks.computeIfAbsent(s.id) { Mutex() }.withLock {
            cache[s.id]?.takeIf { it.server == s && it.key == key && it.at.isAfter(clock.now().minusSeconds(CACHE_SECONDS)) }?.let { return@withLock it }
            refresh(s, key, c?.client ?: client(s, auth))
        }
    }

    private suspend fun refresh(s: McpServerConfig, key: IdentityKey, client: McpClient): Cached? =
        telemetry.span("mcp tools/list ${s.name}", spanAttributes(s, "tools/list"), SpanKind.CLIENT) { span ->
            try {
                val prefix = slugify(s.name).replace('-', '_')
                val names = HashMap<String, String>()
                val tools = client.listTools(span).mapNotNull { t ->
                    val name = t["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (s.toolAllowlist.isNotEmpty() && name !in s.toolAllowlist) return@mapNotNull null
                    val exposed = "${prefix}__$name".take(64)
                    names[exposed] = name
                    ToolSpec(exposed, "[${s.name}] " + (t["description"]?.jsonPrimitive?.content ?: name),
                        (t["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") })
                }
                failing.remove(s.id)
                Cached(clock.now(), s, key, client, tools, names).also { cache[s.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val type = classify(e)
                if (type == BUDGET) pause(s, e as McpHttpException)
                record(span, type, e.message ?: type)
                val msg = "${e.javaClass.simpleName}: ${e.message}"
                if (failing.put(s.id, msg) != msg) log.warning("MCP server '${s.name}' tools/list failed: $msg")
                cache[s.id]?.takeIf { type == BUDGET && it.server == s && it.key == key }
            }
        }

    override suspend fun tools(principal: Principal, internal: Boolean): List<ToolSpec> {
        if (!internal) return emptyList()
        return servers.list().filter { it.enabled }.mapNotNull { s ->
            val c = try { load(s) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return@mapNotNull null
            c.tools
        }.flatten()
    }

    override suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome {
        if (!internal) return ToolOutcome("Unknown tool '$name'", true)
        for (s in servers.list().filter { it.enabled }) {
            val c = try { load(s) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            val remote = c.names[name] ?: continue
            pausedUntil(s)?.let { return ToolOutcome(pausedMessage(s, it), true) }
            return invoke(s, c.client, remote, args)
        }
        return ToolOutcome("Unknown tool '$name'", true)
    }

    private suspend fun invoke(s: McpServerConfig, client: McpClient, tool: String, args: JsonObject): ToolOutcome =
        telemetry.span("mcp tools/call $tool", spanAttributes(s, "tools/call") + ("gen_ai.tool.name" to tool), SpanKind.CLIENT) { span ->
            try {
                client.callTool(tool, args, span).also { if (it.isError) span.set("error.type", TOOL_ERROR) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val type = classify(e)
                val out = when (type) {
                    BUDGET -> pausedMessage(s, pause(s, e as McpHttpException))
                    UNAUTHORIZED -> "The tools gateway did not accept the credential of this agent (HTTP 401) for $tool."
                    else -> e.message ?: "Tool failed"
                }
                record(span, type, out)
                ToolOutcome(out, true)
            }
        }

    private fun pause(s: McpServerConfig, e: McpHttpException): Instant {
        val now = clock.now()
        val wait = (e.cooldown ?: DEFAULT_COOLDOWN).coerceIn(Duration.ofSeconds(1), MAX_PAUSE)
        return now.plus(wait).also { paused[s.id] = it }
    }

    private fun record(span: SpanScope, type: String, message: String) {
        span.set("error.type", type)
        span.fail(message.take(500))
    }

    private fun spanAttributes(s: McpServerConfig, method: String): Map<String, Any?> = mapOf(
        "mcp.method.name" to method,
        "mayday.mcp.server" to s.name,
        "server.address" to runCatching { URI(s.url).host }.getOrNull(),
    )

    private fun pausedMessage(s: McpServerConfig, until: Instant) =
        "The tools gateway refused calls to '${s.name}' (HTTP 429): the budget or rate limit is used up. Do not retry before $until."

    companion object {
        private val log = Logger.getLogger("mayday.mcp")
        const val CACHE_SECONDS = 120L
        val MAX_PAUSE: Duration = Duration.ofHours(24)
        private const val BUDGET = "budget_exhausted"
        private const val TOOL_ERROR = "tool_error"
        private const val UNAUTHORIZED = "unauthorized"
        private const val TRANSPORT = "transport"

        private fun classify(e: Throwable): String = when {
            e is McpHttpException && e.status == 429 -> BUDGET
            e is McpHttpException && e.status == 401 -> UNAUTHORIZED
            else -> TRANSPORT
        }
    }
}
