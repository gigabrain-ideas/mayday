package mayday.contract

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import mayday.adapters.credentials.Credentials
import mayday.agents.ClientAuth
import mayday.agents.CredentialMode
import mayday.agents.CredentialSpec
import mayday.agents.GatewayError
import mayday.agents.OAuthGrant
import mayday.shared.SpanKind
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import mayday.suspendTest
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * In-process OAuth 2.0 authorization server. Each token call issues `t<n>` unless [respond] says otherwise.
 * GET requests answer with [metadata] (issuer metadata documents); null means 404.
 */
class FakeTokenServer : AutoCloseable {
    val calls = AtomicInteger()
    val forms = ConcurrentLinkedQueue<Map<String, String>>()
    val paths = ConcurrentLinkedQueue<String>()
    val authorizations = ConcurrentLinkedQueue<String>()
    val metadataPaths = ConcurrentLinkedQueue<String>()
    @Volatile var metadata: (String) -> String? = { null }
    @Volatile var delayMs = 0L
    @Volatile var respond: (Int) -> Pair<Int, String> = { n -> 200 to """{"access_token":"t$n","token_type":"Bearer","expires_in":3600}""" }

    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@FakeTokenServer.executor
        createContext("/") { ex ->
            ex.use {
                if (ex.requestMethod == "GET") {
                    metadataPaths += ex.requestURI.path
                    val doc = metadata(ex.requestURI.path)?.toByteArray()
                    ex.responseHeaders["Content-Type"] = listOf("application/json")
                    ex.sendResponseHeaders(if (doc == null) 404 else 200, doc?.size?.toLong() ?: -1)
                    doc?.let { ex.responseBody.write(it) }
                    return@use
                }
                val n = calls.incrementAndGet()
                authorizations += ex.requestHeaders.getFirst("Authorization").orEmpty()
                paths += ex.requestURI.path
                forms += String(ex.requestBody.readAllBytes()).split('&').filter { it.isNotEmpty() }.associate {
                    val (k, v) = it.split('=', limit = 2)
                    URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
                }
                if (delayMs > 0) Thread.sleep(delayMs)
                val (status, body) = respond(n)
                val bytes = body.toByteArray()
                ex.responseHeaders["Content-Type"] = listOf("application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.write(bytes)
            }
        }
        start()
    }

    val base get() = "http://127.0.0.1:${server.address.port}"
    val url get() = "$base/oauth2/token"

    override fun close() {
        server.stop(0)
        executor.close()
    }
}

class RecordingTelemetry : Telemetry {
    data class Span(val name: String, val kind: SpanKind, val attributes: MutableMap<String, Any?>, var failure: String? = null)

    val spans = ConcurrentLinkedQueue<Span>()

    override suspend fun <T> span(name: String, attributes: Map<String, Any?>, kind: SpanKind, block: suspend (SpanScope) -> T): T {
        val span = Span(name, kind, attributes.toMutableMap()).also { spans += it }
        return block(object : SpanScope {
            override val traceId = "trace"
            override fun set(key: String, value: Any?) { span.attributes[key] = value }
            override fun event(name: String, attributes: Map<String, Any?>) = Unit
            override fun fail(message: String, error: Throwable?) { span.failure = message }
        })
    }

    override fun propagationHeaders(): Map<String, String> = emptyMap()
}

/**
 * Contract of the credential adapter: standard OAuth 2.0 with any authorization server (client credentials, token
 * exchange, issuer discovery, both client secret methods), workload identity federation and token files.
 */
class CredentialsContractTest {
    private val tokens = FakeTokenServer()
    private val dir: Path = Files.createTempDirectory("mayday-credentials")
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val telemetry = RecordingTelemetry()
    private val credentials = Credentials(telemetry = telemetry, clock = { now })

    @AfterTest
    fun close() {
        tokens.close()
        dir.toFile().deleteRecursively()
    }

    private fun secretSpec(scope: String = "gateway.invoke", clientAuth: ClientAuth = ClientAuth.BASIC) =
        CredentialSpec(CredentialMode.OAUTH2, tokenUrl = tokens.url, clientId = "client-1", clientSecret = "s3cr3t-value", scope = scope, clientAuth = clientAuth)

    private fun assertionSpec(file: Path) =
        CredentialSpec(CredentialMode.OAUTH2, tokenUrl = tokens.url, clientId = "client-1", assertionFile = file.toString(), scope = "gateway.invoke")

    private fun jwt(claims: String) =
        listOf("""{"alg":"none"}""", claims).joinToString(".") { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) } + ".sig"

    @Test
    fun `static and none modes return the configured value`() = suspendTest {
        assertEquals("key-1", credentials.create(CredentialSpec(CredentialMode.STATIC, staticSecret = "key-1")).token())
        assertEquals("", credentials.create(CredentialSpec(CredentialMode.NONE)).token())
    }

    @Test
    fun `equal specs share one provider`() {
        assertSame(credentials.create(secretSpec()), credentials.create(secretSpec()))
    }

    @Test
    fun `client secret goes in HTTP Basic by default`() = suspendTest {
        val spec = secretSpec().copy(clientId = "client:1")
        assertEquals("t1", credentials.create(spec).token())
        assertEquals(mapOf("grant_type" to "client_credentials", "scope" to "gateway.invoke"), tokens.forms.single())
        val expected = Base64.getEncoder().encodeToString("client%3A1:s3cr3t-value".toByteArray())
        assertEquals("Basic $expected", tokens.authorizations.single(), "RFC 6749 2.3.1: id and secret are form-encoded first")
    }

    @Test
    fun `client secret post sends the credentials as form fields`() = suspendTest {
        assertEquals("t1", credentials.create(secretSpec(clientAuth = ClientAuth.POST)).token())
        assertEquals(
            mapOf("grant_type" to "client_credentials", "scope" to "gateway.invoke", "client_id" to "client-1", "client_secret" to "s3cr3t-value"),
            tokens.forms.single(),
        )
        assertEquals("", tokens.authorizations.single())
    }

    @Test
    fun `extra token parameters are sent but cannot replace the fields Mayday sets`() = suspendTest {
        val spec = secretSpec().copy(tokenParams = mapOf("audience" to "https://gateway.example.com", "resource" to "https://gateway.example.com/", "grant_type" to "password"))
        credentials.create(spec).token()
        val form = tokens.forms.single()
        assertEquals("https://gateway.example.com", form["audience"])
        assertEquals("https://gateway.example.com/", form["resource"])
        assertEquals("client_credentials", form["grant_type"])
    }

    @Test
    fun `token exchange sends the platform token as subject token`() = suspendTest {
        val file = dir.resolve("workload-token").also { Files.writeString(it, "platform-jwt\n") }
        val spec = CredentialSpec(
            CredentialMode.OAUTH2, tokenUrl = tokens.url, grant = OAuthGrant.TOKEN_EXCHANGE, assertionFile = file.toString(),
            scope = "gateway.invoke", tokenParams = mapOf("audience" to "gateway", "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token"),
        )
        assertTrue(spec.complete, "token exchange needs no client credentials")
        assertEquals("t1", credentials.create(spec).token())
        assertEquals(
            mapOf(
                "audience" to "gateway",
                "requested_token_type" to "urn:ietf:params:oauth:token-type:access_token",
                "grant_type" to "urn:ietf:params:oauth:grant-type:token-exchange",
                "subject_token" to "platform-jwt",
                "subject_token_type" to "urn:ietf:params:oauth:token-type:jwt",
                "scope" to "gateway.invoke",
            ),
            tokens.forms.single(),
        )
        assertEquals("", tokens.authorizations.single())
    }

    @Test
    fun `token exchange authenticates the client when a secret is set`() = suspendTest {
        val file = dir.resolve("workload-token").also { Files.writeString(it, "platform-jwt") }
        credentials.create(CredentialSpec(CredentialMode.OAUTH2, tokenUrl = tokens.url, grant = OAuthGrant.TOKEN_EXCHANGE,
            assertionFile = file.toString(), clientId = "client-1", clientSecret = "s3cr3t-value", tokenParams = mapOf("subject_token_type" to "urn:x:custom"))).token()
        assertTrue(tokens.authorizations.single().startsWith("Basic "))
        assertEquals("urn:x:custom", tokens.forms.single()["subject_token_type"])
    }

    @Test
    fun `the token endpoint comes from OIDC discovery and is cached`() = suspendTest {
        tokens.metadata = { path -> if (path == "/realms/acme/.well-known/openid-configuration") """{"issuer":"${tokens.base}/realms/acme","token_endpoint":"${tokens.url}"}""" else null }
        val provider = credentials.create(CredentialSpec(CredentialMode.OAUTH2, issuer = "${tokens.base}/realms/acme/", clientId = "c", clientSecret = "s"))
        assertEquals("t1", provider.token())
        provider.invalidate()
        assertEquals("t2", provider.token())
        assertEquals(listOf("/realms/acme/.well-known/openid-configuration"), tokens.metadataPaths.toList())
        assertEquals(listOf("/oauth2/token", "/oauth2/token"), tokens.paths.toList())
    }

    @Test
    fun `discovery falls back to RFC 8414 metadata`() = suspendTest {
        tokens.metadata = { path -> if (path == "/.well-known/oauth-authorization-server/tenant") """{"issuer":"${tokens.base}/tenant","token_endpoint":"${tokens.url}"}""" else null }
        assertEquals("t1", credentials.create(CredentialSpec(CredentialMode.OAUTH2, issuer = "${tokens.base}/tenant", clientId = "c", clientSecret = "s")).token())
        assertEquals(listOf("/tenant/.well-known/openid-configuration", "/.well-known/oauth-authorization-server/tenant"), tokens.metadataPaths.toList())
    }

    @Test
    fun `discovery refuses metadata of another issuer and missing metadata`() = suspendTest {
        tokens.metadata = { """{"issuer":"https://evil.example.com","token_endpoint":"${tokens.url}"}""" }
        val mismatch = assertFailsWith<GatewayError> { credentials.create(CredentialSpec(CredentialMode.OAUTH2, issuer = "${tokens.base}/a", clientId = "c", clientSecret = "s")).token() }
        assertTrue("another issuer" in mismatch.message!!, mismatch.message)
        tokens.metadata = { null }
        val missing = assertFailsWith<GatewayError> { credentials.create(CredentialSpec(CredentialMode.OAUTH2, issuer = "${tokens.base}/b", clientId = "c", clientSecret = "s")).token() }
        assertEquals(404, missing.status)
        assertEquals(0, tokens.calls.get())
    }

    @Test
    fun `assertion grant sends the federated token and reads the file again after rotation`() = suspendTest {
        val file = dir.resolve("workload-token").also { Files.writeString(it, "jwt-one\n") }
        val provider = credentials.create(assertionSpec(file))
        assertEquals("t1", provider.token())
        val form = tokens.forms.first()
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", form["client_assertion_type"])
        assertEquals("jwt-one", form["client_assertion"])
        assertEquals("client-1", form["client_id"])
        assertFalse("client_secret" in form)

        Files.writeString(file, "jwt-two")
        provider.invalidate()
        assertEquals("t2", provider.token())
        assertEquals("jwt-two", tokens.forms.last()["client_assertion"])
    }

    @Test
    fun `concurrent callers cause one fetch`() = suspendTest {
        tokens.delayMs = 200
        val provider = credentials.create(secretSpec())
        val results = (1..1000).map { async(Dispatchers.Default) { provider.token() } }.awaitAll()
        assertEquals(setOf("t1"), results.toSet())
        assertEquals(1, tokens.calls.get())
        assertEquals("t1", provider.token())
        assertEquals(1, tokens.calls.get())
    }

    @Test
    fun `the token is refreshed before it expires`() = suspendTest {
        val provider = credentials.create(secretSpec())
        assertEquals("t1", provider.token())
        now = now.plus(Duration.ofSeconds(3600 - 301))
        assertEquals("t1", provider.token())
        now = now.plus(Duration.ofSeconds(2))
        assertEquals("t2", provider.token())
        assertEquals(2, tokens.calls.get())
    }

    @Test
    fun `without expires_in the exp claim of a JWT access token sets the lifetime`() = suspendTest {
        val tokensIssued = AtomicInteger()
        val exp = now.plusSeconds(600).epochSecond
        tokens.respond = { _ ->
            val token = jwt("{\"n\":${tokensIssued.incrementAndGet()},\"exp\":$exp}")
            200 to "{\"access_token\":\"$token\"}"
        }
        val provider = credentials.create(secretSpec())
        val first = provider.token()
        now = now.plusSeconds(600 - 121)
        assertEquals(first, provider.token())
        now = now.plusSeconds(2)
        assertTrue(first != provider.token())
        assertEquals(2, tokens.calls.get())
    }

    @Test
    fun `invalidate forces a new fetch`() = suspendTest {
        val provider = credentials.create(secretSpec())
        assertEquals("t1", provider.token())
        provider.invalidate()
        assertEquals("t2", provider.token())
    }

    @Test
    fun `a failed refresh keeps a token that is still valid and fails after expiry`() = suspendTest {
        val provider = credentials.create(secretSpec())
        assertEquals("t1", provider.token())
        tokens.respond = { 503 to """{"error":"temporarily_unavailable"}""" }
        now = now.plusSeconds(3400)
        assertEquals("t1", provider.token())
        now = now.plusSeconds(300)
        val error = assertFailsWith<GatewayError> { provider.token() }
        assertTrue("temporarily_unavailable" in error.message!!, error.message)
    }

    @Test
    fun `token endpoint errors report the OAuth error without secrets`() = suspendTest {
        tokens.respond = { 401 to """{"error":"invalid_client","error_description":"Client authentication failed."}""" }
        val error = assertFailsWith<GatewayError> { credentials.create(secretSpec()).token() }
        assertEquals(401, error.status)
        assertTrue("invalid_client: Client authentication failed." in error.message!!, error.message)
        assertFalse("s3cr3t-value" in error.message!!)
        assertFalse("s3cr3t-value" in secretSpec().toString())
        val span = telemetry.spans.single()
        assertEquals("credential.fetch", span.name)
        assertEquals(SpanKind.CLIENT, span.kind)
        assertEquals(401, span.attributes["http.response.status_code"])
        assertTrue(span.failure != null)
        assertFalse(span.attributes.values.any { "s3cr3t-value" in it.toString() })
    }

    @Test
    fun `a missing assertion file and an unreachable endpoint fail clearly`() = suspendTest {
        val missing = assertFailsWith<GatewayError> { credentials.create(assertionSpec(dir.resolve("absent"))).token() }
        assertTrue("does not exist" in missing.message!!, missing.message)
        val down = Credentials(clock = { now })
            .create(CredentialSpec(CredentialMode.OAUTH2, tokenUrl = "http://127.0.0.1:1/token", clientId = "c", clientSecret = "s3cr3t-value"))
        val error = assertFailsWith<GatewayError> { down.token() }
        assertEquals(0, error.status)
        assertFalse("s3cr3t-value" in error.message!!)
    }

    @Test
    fun `fetch spans carry the mode, server, status and lifetime but never the token`() = suspendTest {
        credentials.create(secretSpec()).token()
        val span = telemetry.spans.single()
        assertEquals("oauth2", span.attributes["mayday.credential.mode"])
        assertEquals("client_credentials", span.attributes["mayday.credential.grant"])
        assertEquals("basic", span.attributes["mayday.credential.client_auth"])
        assertEquals("127.0.0.1", span.attributes["server.address"])
        assertEquals(200, span.attributes["http.response.status_code"])
        assertEquals(3600L, span.attributes["mayday.credential.expires_in"])
        assertFalse(span.attributes.values.any { it.toString() == "t1" })
    }

    @Test
    fun `file mode reads the token again when the file changes`() = suspendTest {
        val file = dir.resolve("token").also { Files.writeString(it, " file-one \n") }
        val provider = credentials.create(CredentialSpec(CredentialMode.FILE, tokenFile = file.toString()))
        assertEquals("file-one", provider.token())
        assertEquals("file-one", provider.token())
        assertEquals(1, telemetry.spans.size)
        Files.writeString(file, "file-two")
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)))
        assertEquals("file-two", provider.token())
    }

    @Test
    fun `file mode fails when the file is missing`() = suspendTest {
        val provider = credentials.create(CredentialSpec(CredentialMode.FILE, tokenFile = dir.resolve("nope").toString()))
        val error = assertFailsWith<GatewayError> { provider.token() }
        assertTrue("does not exist" in error.message!!, error.message)
    }
}
