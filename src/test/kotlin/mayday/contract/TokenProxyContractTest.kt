package mayday.contract

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mayday.adapters.credentials.Credentials
import mayday.agents.AuthStyle
import mayday.agents.CredentialMode
import mayday.agents.CredentialSpec
import mayday.agents.GatewayAuth
import mayday.sidecar.TokenProxy
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract of the token proxy sidecar: identity injection, 401 refresh, 429 pass-through and streaming. */
class TokenProxyContractTest {
    private class Seen(val method: String, val uri: String, val headers: Map<String, List<String>>, val body: String) {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.single()
    }

    private val seen = ConcurrentLinkedQueue<Seen>()
    private val upstreamCalls = AtomicInteger()
    @Volatile private var upstreamHandler: (HttpExchange, Seen) -> Unit = { ex, _ -> send(ex, 200, "ok") }

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@TokenProxyContractTest.executor
        createContext("/") { ex ->
            ex.use {
                upstreamCalls.incrementAndGet()
                val s = Seen(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.toMap(), String(ex.requestBody.readAllBytes()))
                seen += s
                upstreamHandler(ex, s)
            }
        }
        start()
    }
    private val upstreamUrl = "http://127.0.0.1:${upstream.address.port}/gateway"
    private val tokens = FakeTokenServer()
    private val client = HttpClient.newHttpClient()
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest
    fun close() {
        closeables.forEach { it.close() }
        upstream.stop(0)
        executor.close()
        tokens.close()
    }

    private fun send(ex: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders[k] = listOf(v) }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
    }

    private fun startProxy(auth: GatewayAuth): String {
        val running = TokenProxy.start(TokenProxy.Config(InetSocketAddress("127.0.0.1", 0), URI(upstreamUrl), auth))
        closeables += running
        return "http://127.0.0.1:${running.address.port}"
    }

    private fun oauth(): GatewayAuth {
        val provider = Credentials().create(CredentialSpec(CredentialMode.OAUTH2, tokenUrl = tokens.url, clientId = "c", clientSecret = "s3cr3t"))
        return GatewayAuth(AuthStyle.BEARER, provider, mapOf("x-company-agent-id" to "agent-42"))
    }

    private fun get(base: String, path: String) =
        client.send(HttpRequest.newBuilder(URI("$base$path")).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `the proxy injects identity headers from the environment and strips client credentials`() {
        val running = TokenProxy.start(
            TokenProxy.fromEnv(
                mapOf(
                    "MAYDAY_PROXY_LISTEN" to "127.0.0.1:0",
                    "MAYDAY_PROXY_UPSTREAM" to upstreamUrl,
                    "MAYDAY_LLM_CREDENTIAL" to "oauth2",
                    "MAYDAY_LLM_TOKEN_URL" to tokens.url,
                    "MAYDAY_LLM_CLIENT_ID" to "client-1",
                    "MAYDAY_LLM_CLIENT_SECRET" to "s3cr3t",
                    "MAYDAY_LLM_HEADERS" to "x-company-agent-id=agent-42,x-team=sre",
                ),
            ),
        ).also { closeables += it }
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${running.address.port}/v1/messages?beta=true"))
            .header("Authorization", "Bearer fixed-key")
            .header("x-api-key", "fixed-key")
            .header("x-company-agent-id", "spoofed")
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString("""{"model":"m"}"""))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        val s = seen.single()
        assertEquals("POST", s.method)
        assertEquals("/gateway/v1/messages?beta=true", s.uri)
        assertEquals("""{"model":"m"}""", s.body)
        assertEquals("Bearer t1", s.header("Authorization"))
        assertNull(s.header("x-api-key"))
        assertEquals("agent-42", s.header("x-company-agent-id"))
        assertEquals("sre", s.header("x-team"))
        assertEquals("2023-06-01", s.header("anthropic-version"))
        assertEquals("client_credentials", tokens.forms.single()["grant_type"])
    }

    @Test
    fun `generic OAuth 2 variables configure discovery and token exchange`() {
        val file = Files.createTempFile("workload-token", "").also { Files.writeString(it, "platform-jwt") }
        tokens.metadata = { path -> if (path == "/idp/.well-known/openid-configuration") """{"issuer":"${tokens.base}/idp","token_endpoint":"${tokens.url}"}""" else null }
        val running = TokenProxy.start(
            TokenProxy.fromEnv(
                mapOf(
                    "MAYDAY_PROXY_LISTEN" to "127.0.0.1:0",
                    "MAYDAY_PROXY_UPSTREAM" to upstreamUrl,
                    "MAYDAY_LLM_CREDENTIAL" to "oauth2",
                    "MAYDAY_LLM_AUTH_STYLE" to "api_key",
                    "MAYDAY_LLM_ISSUER" to "${tokens.base}/idp",
                    "MAYDAY_LLM_GRANT" to "token_exchange",
                    "MAYDAY_LLM_ASSERTION_FILE" to file.toString(),
                    "MAYDAY_LLM_TOKEN_PARAMS" to "audience=gateway",
                ),
            ),
        ).also { closeables += it }
        assertEquals(200, get("http://127.0.0.1:${running.address.port}", "/v1/models").statusCode())
        assertEquals("/oauth2/token", tokens.paths.single())
        val form = tokens.forms.single()
        assertEquals("urn:ietf:params:oauth:grant-type:token-exchange", form["grant_type"])
        assertEquals("platform-jwt", form["subject_token"])
        assertEquals("gateway", form["audience"])
        assertEquals("t1", seen.single().header("x-api-key"))
        Files.delete(file)
    }

    @Test
    fun `token rotation is visible across requests`() {
        val file = Files.createTempFile("proxy-token", "").also { Files.writeString(it, "file-one") }
        val provider = Credentials().create(CredentialSpec(CredentialMode.FILE, tokenFile = file.toString()))
        val base = startProxy(GatewayAuth(AuthStyle.BEARER, provider))
        get(base, "/a")
        Files.writeString(file, "file-two")
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)))
        get(base, "/b")
        assertEquals(listOf("Bearer file-one", "Bearer file-two"), seen.map { it.header("Authorization") })
        Files.delete(file)
    }

    @Test
    fun `HTTP 429 passes through with Retry-After and is not retried`() {
        upstreamHandler = { ex, _ -> send(ex, 429, """{"error":"budget"}""", mapOf("Retry-After" to "7")) }
        val response = get(startProxy(oauth()), "/v1/messages")
        assertEquals(429, response.statusCode())
        assertEquals("7", response.headers().firstValue("Retry-After").orElse(null))
        assertEquals("""{"error":"budget"}""", response.body())
        assertEquals(1, upstreamCalls.get())
    }

    @Test
    fun `HTTP 401 refreshes the token once and retries the request once`() {
        upstreamHandler = { ex, s -> if (s.header("Authorization") == "Bearer t1") send(ex, 401, "expired") else send(ex, 200, "fresh") }
        val request = HttpRequest.newBuilder(URI("${startProxy(oauth())}/v1/messages"))
            .POST(HttpRequest.BodyPublishers.ofString("payload")).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertEquals("fresh", response.body())
        assertEquals(2, tokens.calls.get())
        assertEquals(listOf("payload", "payload"), seen.map { it.body })

        upstreamHandler = { ex, _ -> send(ex, 401, "denied") }
        upstreamCalls.set(0)
        assertEquals(401, get(startProxy(oauth()), "/again").statusCode())
        assertEquals(2, upstreamCalls.get())
    }

    @Test
    fun `streamed responses reach the client before the upstream finishes`() {
        val release = CountDownLatch(1)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        upstreamHandler = { ex, _ ->
            ex.responseHeaders["Content-Type"] = listOf("text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("data: first\n\n".toByteArray())
            ex.responseBody.flush()
            release.await(10, TimeUnit.SECONDS)
            finished.set(true)
            ex.responseBody.write("data: last\n\n".toByteArray())
        }
        val response = client.send(
            HttpRequest.newBuilder(URI("${startProxy(oauth())}/v1/messages")).build(),
            HttpResponse.BodyHandlers.ofLines(),
        )
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElse(null))
        val lines = response.body().iterator()
        assertEquals("data: first", lines.next())
        assertEquals(false, finished.get(), "the first event arrived while the upstream still waits")
        release.countDown()
        val rest = generateSequence { if (lines.hasNext()) lines.next() else null }.toList()
        assertTrue("data: last" in rest)
    }

    @Test
    fun `healthz answers without calling the upstream`() {
        val response = get(startProxy(oauth()), "/healthz")
        assertEquals(200, response.statusCode())
        assertEquals(0, upstreamCalls.get())
    }
}
