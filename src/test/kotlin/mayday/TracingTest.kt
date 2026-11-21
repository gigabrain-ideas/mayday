package mayday

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mayday.adapters.mcp.McpServer
import mayday.adapters.memory.MemoryStore
import mayday.adapters.telemetry.OtelTelemetry
import mayday.agents.AgentAction
import mayday.agents.AgentConfig
import mayday.agents.AgentDispatcher
import mayday.agents.DispatchPayload
import mayday.agents.GatewayAuth
import mayday.agents.LlmBlock
import mayday.agents.LlmConfig
import mayday.agents.LlmInput
import mayday.agents.LlmMessage
import mayday.agents.LlmProvider
import mayday.agents.LlmProviderFactory
import mayday.agents.LlmRequest
import mayday.agents.LlmResponse
import mayday.agents.LlmRole
import mayday.agents.RunStatus
import mayday.agents.StopReason
import mayday.agents.SubjectKind
import mayday.alerting.AlertSignal
import mayday.identity.OrgRole
import mayday.identity.TeamRole
import mayday.identity.User
import mayday.incidents.IncidentPatch
import mayday.incidents.IncidentStatus
import mayday.incidents.NewIncident
import mayday.shared.Ids
import mayday.shared.Severity
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import mayday.adapters.grpc.Grpc
import mayday.api.v1.DeclareIncidentRequest
import mayday.api.v1.IncidentServiceGrpcKt
import mayday.contract.GrpcPort
import mayday.identity.TokenScope
import mayday.shared.Telemetry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The application emits a meaningful trace for alert intake, incident changes, agent runs and MCP tool calls. */
class TracingTest {
    /** Scripted LLM: first a tool call, then a final answer. */
    private class ScriptedLlm : LlmProvider, LlmProviderFactory {
        val script = ArrayDeque<LlmResponse>()
        override fun create(config: LlmConfig, auth: GatewayAuth) = this
        override suspend fun complete(request: LlmRequest): LlmResponse = synchronized(script) { script.pollFirst() }
            ?: LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.Text("done"))), StopReason.END)

        fun tool(name: String, input: JsonObject) = script.addLast(LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.ToolUse(Ids.new(), name, input))), StopReason.TOOL_USE))
        fun answer(text: String) = script.addLast(LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.Text(text))), StopReason.END))
    }

    private val exporter = InMemorySpanExporter.create()
    private val sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()
    private val telemetry = OtelTelemetry(sdk)
    private val llm = ScriptedLlm()
    private val store = MemoryStore(null)
    private val app = App(
        AppSettings(secretKey = "test-secret", publicUrl = "http://mayday.test"),
        Ports.memory(store).with(telemetry = telemetry, llm = llm, dispatcher = object : AgentDispatcher {
            override suspend fun dispatch(agent: AgentConfig, secret: String, payload: DispatchPayload) = Unit
        }),
    )
    private val team = runBlocking {
        app.start()
        app.directory.createTeam("Checkout", "checkout", app.directory.createProject("Payments", "payments").id)
    }
    private fun user(email: String, role: OrgRole, teams: Map<String, TeamRole> = emptyMap()) =
        User(Ids.new(), email, email.substringBefore('@'), role, teams, createdAt = Instant.now()).also { runBlocking { store.users.save(it) } }
    private val admin = app.identity.principalForUser(user("admin@example.com", OrgRole.ADMIN))
    private val member = app.identity.principalForUser(user("member@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.MEMBER)))

    @AfterEach
    fun close() {
        app.stop()
        sdk.close()
    }

    private fun spans(): List<SpanData> = exporter.finishedSpanItems
    private fun named(name: String) = spans().single { it.name == name }
    private fun SpanData.str(key: String) = attributes.get(AttributeKey.stringKey(key))
    private fun children(parent: SpanData) = spans().filter { it.parentSpanId == parent.spanId && it.traceId == parent.traceId }

    @Test
    fun `alert intake records the outcome and the automatic incident`() = suspendTest {
        app.alerts.ingest(admin, AlertSignal("DB down", Severity.SEV1, "checkout", source = "prometheus"))
        app.alerts.ingest(admin, AlertSignal("DB down", Severity.SEV1, "checkout", source = "prometheus"))

        val ingests = spans().filter { it.name == "alert.ingest" }
        assertEquals(listOf("created", "deduplicated"), ingests.map { it.str("mayday.alert.outcome") })
        val first = ingests.first()
        assertEquals("prometheus", first.str("mayday.alert.source"))
        assertEquals("SEV1", first.str("mayday.alert.severity"))
        assertEquals(team.id, first.str("mayday.alert.team_id"))
        val declare = children(first).single { it.name == "incident.declare" }
        assertEquals("alert", declare.str("mayday.incident.trigger"))
        assertEquals(first.str("mayday.incident.key"), declare.str("mayday.incident.key"))
    }

    @Test
    fun `incident status changes and denied requests are visible`() = suspendTest {
        val inc = app.incidents.declare(member, NewIncident("Checkout slow", severity = Severity.SEV3, teamIds = listOf(team.id)))
        app.incidents.update(member, inc.key, IncidentPatch(status = IncidentStatus.RESOLVED))
        val update = named("incident.update")
        assertEquals("INVESTIGATING", update.str("mayday.incident.status.from"))
        assertEquals("RESOLVED", update.str("mayday.incident.status.to"))
        assertTrue(update.events.any { it.name == "incident.resolved" })
        assertEquals("manual", named("incident.declare").str("mayday.incident.trigger"))

        val viewer = app.identity.principalForUser(user("viewer@example.com", OrgRole.VIEWER))
        runCatching { app.incidents.declare(viewer, NewIncident("No", severity = Severity.SEV3, teamIds = listOf(team.id))) }
        val denied = spans().last { it.name == "incident.declare" }
        assertEquals(StatusCode.ERROR, denied.status.statusCode)
        assertEquals("forbidden", denied.str("error.type"))
    }

    @Test
    fun `an internal agent run is one trace with chat and tool spans`() = suspendTest {
        app.agents.updateLlm(admin, LlmInput(apiKey = "sk-test", enabled = true))
        val inc = app.incidents.declare(member, NewIncident("Pool exhausted", severity = Severity.SEV3, teamIds = listOf(team.id)))
        llm.tool("update_incident", buildJsonObject { put("ref", inc.key); put("status", "IDENTIFIED") })
        llm.answer("Summary: the pool is exhausted.")
        exporter.reset()

        val run = app.agents.trigger(member, AgentAction.ANALYZE, SubjectKind.INCIDENT, inc.key, null, "")
        eventually { store.runs.get(run.id)!!.status.terminal }
        eventually { spans().any { it.name.startsWith("invoke_agent") } }
        val done = store.runs.get(run.id)!!
        assertEquals(RunStatus.SUCCEEDED, done.status, done.error)

        val root = spans().single { it.name.startsWith("invoke_agent") }
        assertEquals(done.traceId, root.traceId)
        assertEquals(run.id, root.str("gen_ai.conversation.id"))
        assertEquals("invoke_agent", root.str("gen_ai.operation.name"))
        val kids = children(root)
        assertEquals(2, kids.count { it.name.startsWith("chat") })
        val tool = kids.single { it.name == "execute_tool update_incident" }
        assertEquals("update_incident", tool.str("gen_ai.tool.name"))
        val update = children(tool).single { it.name == "incident.update" }
        assertEquals("AGENT", update.str("mayday.actor.kind"))
        assertEquals("IDENTIFIED", update.str("mayday.incident.status.to"))
        assertTrue(spans().none { it.attributes.get(AttributeKey.stringKey("gen_ai.tool.call.arguments")) != null }, "content capture is off")
    }

    @Test
    fun `MCP tool calls record the tool, the caller kind and errors`() = suspendTest {
        val mcp = McpServer(app.maydayTools, VERSION, telemetry)
        fun call(name: String, args: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", args) }
        }
        mcp.handle(member, call("list_incidents", JsonObject(emptyMap())))
        mcp.handle(member, call("update_incident", buildJsonObject { put("ref", "INC-404") }))
        mcp.handle(member, call("set_setting", JsonObject(emptyMap())))

        val ok = named("tools/call list_incidents")
        assertEquals(StatusCode.UNSET, ok.status.statusCode)
        assertEquals("USER", ok.str("mayday.actor.kind"))
        assertEquals(false, ok.attributes.get(AttributeKey.booleanKey("mayday.actor.delegated")))
        assertEquals("not_found", named("tools/call update_incident").str("error.type"))
        assertEquals("-32602", named("tools/call set_setting").str("error.type"))
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TracingServerConfig {
    @Bean fun spanExporter(): InMemorySpanExporter = InMemorySpanExporter.create()
    @Bean fun tracingGrpcPort() = GrpcPort()
}

/** The real Spring application: server spans for HTTP and gRPC, domain spans below them, no OTLP export without an endpoint. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mayday.data-dir=none",
        "mayday.secret-key=tracing-test-secret",
        "spring.grpc.server.port=0",
        "server.address=127.0.0.1",
        "spring.grpc.server.address=127.0.0.1",
        "spring.grpc.server.inprocess.name=tracing-test",
        "management.opentelemetry.tracing.export.schedule-delay=10ms",
    ],
)
@Import(TracingServerConfig::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracingServerTest {
    @LocalServerPort private var httpPort = 0
    @Autowired private lateinit var app: App
    @Autowired private lateinit var exporter: InMemorySpanExporter
    @Autowired private lateinit var grpcPort: GrpcPort
    @Autowired private lateinit var context: ApplicationContext

    private val token by lazy {
        runBlocking {
            if (app.directory.teamOrNull("checkout") == null) app.directory.createTeam("Checkout", "checkout", null)
            val u = User(Ids.new(), "ops@example.com", "ops", OrgRole.ADMIN, createdAt = Instant.now()).also { app.ports.users.save(it) }
            app.identity.issueToken(app.identity.principalForUser(u), "tracing", TokenScope.entries.toSet(), null).secret
        }
    }

    private fun spans() = exporter.finishedSpanItems
    private fun parentOf(s: SpanData) = spans().single { it.spanId == s.parentSpanId && it.traceId == s.traceId }

    @Test
    fun `without an OTLP endpoint nothing is exported over the network`() {
        val otlp = context.beanDefinitionNames.mapNotNull { context.getType(it)?.name }.filter { Regex("Otlp\\w*(Exporter|MeterRegistry)$").containsMatchIn(it) }
        assertEquals(emptyList(), otlp)
        assertTrue(context.getBean(Telemetry::class.java) is OtelTelemetry)
        assertTrue(app.ports.telemetry is OtelTelemetry)
    }

    @Test
    fun `an HTTP alert intake is a server span with the domain span below it`() = suspendTest {
        val r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort/api/v1/alerts"))
                .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"title":"Disk full ${Ids.new()}","team":"checkout","severity":"SEV4"}""")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(201, r.statusCode(), r.body())
        eventually { spans().any { it.name == "alert.ingest" } }
        val ingest = spans().last { it.name == "alert.ingest" }
        eventually { spans().any { it.spanId == ingest.parentSpanId } }
        assertEquals(io.opentelemetry.api.trace.SpanKind.SERVER, parentOf(ingest).kind)
    }

    @Test
    fun `a gRPC call is a server span with the domain span below it`() = suspendTest {
        val channel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort.port).usePlaintext().build()
        try {
            val headers = Metadata().apply { put(Grpc.AUTHORIZATION, "Bearer $token") }
            IncidentServiceGrpcKt.IncidentServiceCoroutineStub(channel).declareIncident(
                DeclareIncidentRequest.newBuilder().setTitle("Traced").setSeverity(mayday.api.v1.Severity.SEVERITY_SEV4).addTeamIds("checkout").build(), headers,
            )
        } finally {
            channel.shutdownNow()
        }
        eventually { spans().any { it.name == "incident.declare" } }
        val declare = spans().last { it.name == "incident.declare" }
        eventually { spans().any { it.spanId == declare.parentSpanId } }
        val server = parentOf(declare)
        assertEquals(io.opentelemetry.api.trace.SpanKind.SERVER, server.kind)
        assertTrue(server.name.contains("DeclareIncident"), server.name)
    }

    @Test
    fun `a gRPC-Web call is an HTTP server span with the gRPC server span and the domain span below it`() = suspendTest {
        val msg = DeclareIncidentRequest.newBuilder().setTitle("Traced web").setSeverity(mayday.api.v1.Severity.SEVERITY_SEV4).addTeamIds("checkout").build().toByteArray()
        val body = java.nio.ByteBuffer.allocate(5 + msg.size).put(0).putInt(msg.size).put(msg).array()
        val r = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$httpPort/mayday.v1.IncidentService/DeclareIncident"))
                .header("Authorization", "Bearer $token").header("Content-Type", "application/grpc-web+proto")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, r.statusCode())
        eventually { spans().any { it.name == "incident.declare" && it.traceId != "" && spans().count { s -> s.traceId == it.traceId && s.kind == io.opentelemetry.api.trace.SpanKind.SERVER } >= 2 } }
    }
}
