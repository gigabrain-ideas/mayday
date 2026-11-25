package mayday.contract

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import mayday.App
import mayday.FakeLlm
import mayday.Ports
import mayday.RecordingDispatcher
import mayday.adapters.grpc.Grpc
import mayday.adapters.memory.MemoryStore
import mayday.directory.Team
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.identity.TeamRole
import mayday.identity.TokenScope
import mayday.identity.User
import mayday.shared.Ids
import mayday.shared.JSON
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

/** Captures the port of the network gRPC server (the in-process server reports no port). */
class GrpcPort : ApplicationListener<GrpcServerStartedEvent> {
    @Volatile var port = -1
    override fun onApplicationEvent(event: GrpcServerStartedEvent) {
        if (event.port > 0) port = event.port
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestServerConfig {
    @Bean fun fakeLlm() = FakeLlm()
    @Bean fun recordingDispatcher() = RecordingDispatcher()
    @Bean fun grpcPort() = GrpcPort()
    @Bean fun auditSink() = mayday.RecordingSink()
    @Bean fun recordingPager() = mayday.RecordingPager()

    @Bean(destroyMethod = "")
    @Primary
    fun testPorts(llm: FakeLlm, dispatcher: RecordingDispatcher, auditSink: mayday.RecordingSink, pager: mayday.RecordingPager): Ports =
        Ports.memory(MemoryStore(null)).with(llm = llm, dispatcher = dispatcher, audit = listOf(auditSink), pager = pager)
}

/**
 * Starts the real Spring Boot application on random HTTP and gRPC ports with in-memory storage, a fake LLM and a recording dispatcher.
 * Each subclass sets its own spring.grpc.server.inprocess.name with @TestPropertySource, so cached contexts do not collide.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mayday.data-dir=none",
        "mayday.dev-login=true",
        "mayday.secret-key=contract-test-secret",
        "mayday.admin-emails=admin@example.com",
        "spring.grpc.server.port=0",
        "server.address=127.0.0.1",
        "spring.grpc.server.address=127.0.0.1",
    ],
)
@Import(TestServerConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ServerContract {
    @LocalServerPort protected var httpPort = 0
    @Autowired protected lateinit var app: App
    @Autowired protected lateinit var llm: FakeLlm
    @Autowired protected lateinit var dispatcher: RecordingDispatcher
    @Autowired private lateinit var grpcPort: GrpcPort

    protected val http: HttpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
    protected val base get() = "http://127.0.0.1:$httpPort"
    protected lateinit var team: Team
    protected lateinit var other: Team
    private val users = ConcurrentHashMap<String, User>()
    private val tokens = ConcurrentHashMap<String, String>()

    protected val channel: ManagedChannel by lazy { ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort.port).usePlaintext().build() }

    /** The clients connect to 127.0.0.1. If another server could bind the same loopback port, it would receive their calls. */
    @Test
    fun `no other loopback listener can take the ports of the test servers`() {
        for (port in listOf(httpPort, grpcPort.port)) {
            val shadow = runCatching { com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", port), 0) }.getOrNull()
            shadow?.stop(0)
            assertNull(shadow, "another server could bind 127.0.0.1:$port next to the test server")
        }
    }

    @BeforeEach
    fun seed() = runBlocking {
        if (::team.isInitialized) return@runBlocking
        val project = app.directory.createProject("Payments", "payments")
        team = app.directory.createTeam("Checkout", "checkout", project.id)
        other = app.directory.createTeam("SRE", "sre", null)
        user("admin@example.com", OrgRole.ADMIN)
        user("lead@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.ADMIN))
        user("member@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.MEMBER))
        user("viewer@example.com", OrgRole.VIEWER)
    }

    @AfterAll
    fun closeChannel() {
        channel.shutdownNow()
    }

    private suspend fun user(email: String, role: OrgRole, teams: Map<String, TeamRole> = emptyMap()) {
        val u = User(Ids.new(), email, email.substringBefore('@'), role, teams, createdAt = Instant.now())
        app.ports.users.save(u)
        users[email.substringBefore('@')] = u
    }

    protected fun userOf(name: String): User = users.getValue(name)
    protected fun principal(name: String): Principal = app.identity.principalForUser(userOf(name))

    /** A personal API token with every scope the user may hold. */
    protected fun token(name: String): String = tokens.computeIfAbsent(name) {
        runBlocking { app.identity.issueToken(principal(name), "contract", TokenScope.entries.toSet(), null).secret }
    }

    protected fun bearer(secret: String) = Metadata().apply { put(Grpc.AUTHORIZATION, "Bearer $secret") }
    protected fun auth(name: String) = bearer(token(name))

    protected suspend fun assertStatus(code: Status.Code, domainCode: String?, block: suspend () -> Unit): StatusException {
        val e = assertFailsWith<StatusException> { block() }
        assertEquals(code, e.status.code, e.status.toString())
        if (domainCode != null) assertEquals(domainCode, e.trailers?.get(Grpc.ERROR_CODE))
        return e
    }

    protected fun send(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), contentType: String = "application/json"): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(base + path)).timeout(java.time.Duration.ofSeconds(30)).method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) b.header("Content-Type", contentType)
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun json(r: HttpResponse<String>): JsonElement = JSON.parseToJsonElement(r.body())

    /** Dev login; returns the session cookie pair (name=value). */
    protected fun login(email: String): String {
        val r = send("POST", "/auth/dev-login", """{"email":"$email"}""")
        assertEquals(200, r.statusCode(), r.body())
        return r.headers().firstValue("Set-Cookie").get().substringBefore(';')
    }
}
