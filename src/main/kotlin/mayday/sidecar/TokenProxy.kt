package mayday.sidecar

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import mayday.MaydayProperties
import mayday.adapters.credentials.Credentials
import mayday.agents.AuthStyle
import mayday.agents.ClientAuth
import mayday.agents.CredentialMode
import mayday.agents.CredentialProviderFactory
import mayday.agents.CredentialSpec
import mayday.agents.GatewayAuth
import mayday.agents.GatewayError
import mayday.agents.OAuthGrant
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Local reverse proxy (`java -jar mayday.jar token-proxy`). A tool that knows only a fixed key points at it;
 * the proxy adds the fresh token and the agent identity headers to each request for the upstream gateway.
 */
object TokenProxy {
    data class Config(
        val listen: InetSocketAddress,
        val upstream: URI,
        val auth: GatewayAuth,
        val maxReplayBytes: Int = 8 * 1024 * 1024,
    )

    class Running internal constructor(private val server: HttpServer, private val executor: java.util.concurrent.ExecutorService) : AutoCloseable {
        val address: InetSocketAddress get() = server.address
        override fun close() {
            server.stop(0)
            executor.close()
        }
    }

    private val log = Logger.getLogger("mayday.token-proxy")
    private val HOP_BY_HOP = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "trailers",
        "transfer-encoding", "upgrade", "host", "content-length", "expect", "http2-settings",
    )

    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        val config = try {
            fromEnv(System.getenv())
        } catch (e: IllegalArgumentException) {
            System.err.println("token-proxy: ${e.message}")
            kotlin.system.exitProcess(2)
        }
        val running = start(config)
        Runtime.getRuntime().addShutdownHook(Thread { running.close() })
        log.info("token-proxy listens on ${running.address.hostString}:${running.address.port} and forwards to ${config.upstream}")
        Thread.currentThread().join()
    }

    /** Builds the configuration with the same variable rules as the application (MaydayProperties.Llm). */
    fun fromEnv(env: Map<String, String>, credentials: CredentialProviderFactory = Credentials()): Config {
        fun e(name: String) = env[name].orEmpty()
        val upstream = e("MAYDAY_PROXY_UPSTREAM").trim().ifEmpty { throw IllegalArgumentException("MAYDAY_PROXY_UPSTREAM is required") }
        val upstreamUri = runCatching { URI(upstream.trimEnd('/')) }.getOrNull()
            ?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrEmpty() }
            ?: throw IllegalArgumentException("MAYDAY_PROXY_UPSTREAM must be an http(s) URL")
        val listen = e("MAYDAY_PROXY_LISTEN").trim().ifEmpty { "127.0.0.1:8787" }
        val colon = listen.lastIndexOf(':')
        val port = listen.substring(colon + 1).toIntOrNull() ?: throw IllegalArgumentException("MAYDAY_PROXY_LISTEN must be host:port")
        val host = if (colon > 0) listen.substring(0, colon).removePrefix("[").removeSuffix("]") else "127.0.0.1"
        val llm = MaydayProperties.Llm(
            apiKey = e("MAYDAY_LLM_API_KEY").ifBlank { e("ANTHROPIC_API_KEY") },
            authStyle = e("MAYDAY_LLM_AUTH_STYLE"),
            credential = e("MAYDAY_LLM_CREDENTIAL"),
            tokenUrl = e("MAYDAY_LLM_TOKEN_URL"),
            issuer = e("MAYDAY_LLM_ISSUER"),
            grant = e("MAYDAY_LLM_GRANT"),
            clientAuth = e("MAYDAY_LLM_CLIENT_AUTH"),
            clientId = e("MAYDAY_LLM_CLIENT_ID"),
            clientSecret = e("MAYDAY_LLM_CLIENT_SECRET"),
            assertionFile = e("MAYDAY_LLM_ASSERTION_FILE"),
            tokenFile = e("MAYDAY_LLM_TOKEN_FILE"),
            scope = e("MAYDAY_LLM_SCOPE"),
            tokenParams = e("MAYDAY_LLM_TOKEN_PARAMS"),
            headers = e("MAYDAY_LLM_HEADERS"),
        ).environment()
        val mode = llm.credential ?: if (llm.apiKey != null) CredentialMode.STATIC else CredentialMode.NONE
        val spec = CredentialSpec(
            mode = mode,
            staticSecret = llm.apiKey.orEmpty(),
            tokenUrl = llm.tokenUrl.orEmpty(),
            issuer = llm.issuer.orEmpty(),
            grant = llm.grant ?: OAuthGrant.CLIENT_CREDENTIALS,
            clientAuth = llm.clientAuth ?: ClientAuth.BASIC,
            clientId = llm.clientId.orEmpty(),
            clientSecret = llm.clientSecret.orEmpty(),
            assertionFile = llm.assertionFile.orEmpty(),
            tokenFile = llm.tokenFile.orEmpty(),
            scope = llm.scope.orEmpty(),
            tokenParams = llm.tokenParams.orEmpty(),
        )
        val provider = if (mode == CredentialMode.NONE) null else credentials.create(spec)
        val auth = GatewayAuth(llm.authStyle ?: AuthStyle.BEARER, provider, llm.headers.orEmpty())
        return Config(InetSocketAddress(host, port), upstreamUri, auth)
    }

    fun start(
        config: Config,
        http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
    ): Running {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(config.listen, 0)
        server.executor = executor
        server.createContext("/") { ex -> ex.use { handle(it, config, http) } }
        server.start()
        return Running(server, executor)
    }

    private class Body(val bytes: ByteArray?, val stream: InputStream?) {
        val replayable get() = bytes != null
        fun publisher(): HttpRequest.BodyPublisher = when {
            bytes != null && bytes.isEmpty() -> HttpRequest.BodyPublishers.noBody()
            bytes != null -> HttpRequest.BodyPublishers.ofByteArray(bytes)
            else -> HttpRequest.BodyPublishers.ofInputStream { stream }
        }
    }

    private fun readBody(input: InputStream, limit: Int): Body {
        val head = input.readNBytes(limit)
        if (head.size < limit) return Body(head, null)
        val next = input.read()
        if (next < 0) return Body(head, null)
        return Body(null, SequenceInputStream(ByteArrayInputStream(head + next.toByte()), input))
    }

    private fun handle(ex: HttpExchange, config: Config, http: HttpClient) {
        val path = ex.requestURI.rawPath.orEmpty()
        if (path == "/healthz" && ex.requestMethod == "GET") return reply(ex, 200, "ok")
        val body = readBody(ex.requestBody, config.maxReplayBytes)
        val target = URI.create(config.upstream.toString() + path + (ex.requestURI.rawQuery?.let { "?$it" } ?: ""))
        try {
            var response = send(ex, config, http, target, body)
            if (response.statusCode() == 401 && body.replayable) {
                response.body().close()
                config.auth.invalidate()
                response = send(ex, config, http, target, body)
            }
            relay(ex, response)
        } catch (e: GatewayError) {
            reply(ex, 503, "token-proxy: no credential: ${e.message}")
        } catch (e: IOException) {
            if (ex.responseCode > 0) return
            reply(ex, 502, "token-proxy: upstream ${config.upstream.host} failed (${e.javaClass.simpleName})")
        }
    }

    private fun send(ex: HttpExchange, config: Config, http: HttpClient, target: URI, body: Body): HttpResponse<InputStream> {
        val auth = runBlocking { config.auth.headers() }
        val replaced = (auth.keys + config.auth.headers.keys).mapTo(HashSet()) { it.trim().lowercase() } + setOf("authorization", "x-api-key")
        val builder = HttpRequest.newBuilder(target).method(ex.requestMethod, body.publisher())
        ex.requestHeaders.forEach { (name, values) ->
            val n = name.lowercase()
            if (n in HOP_BY_HOP || n in replaced) return@forEach
            values.forEach { v -> runCatching { builder.header(name, v) } }
        }
        auth.forEach { (k, v) -> builder.setHeader(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun relay(ex: HttpExchange, response: HttpResponse<InputStream>) {
        response.headers().map().forEach { (name, values) ->
            val n = name.lowercase()
            if (n.startsWith(":") || n in HOP_BY_HOP) return@forEach
            ex.responseHeaders[name] = values
        }
        val status = response.statusCode()
        val declared = response.headers().firstValueAsLong("content-length").orElse(-1)
        val noBody = ex.requestMethod == "HEAD" || status == 204 || status == 304 || declared == 0L
        ex.sendResponseHeaders(status, if (noBody) -1 else if (declared > 0) declared else 0)
        response.body().use { input ->
            if (noBody) return
            val out = ex.responseBody
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                out.flush()
            }
        }
    }

    private fun reply(ex: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        ex.responseHeaders["Content-Type"] = listOf("text/plain; charset=utf-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }
}
