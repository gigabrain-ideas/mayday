package mayday.contract

import com.google.protobuf.Empty
import io.grpc.Status
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.RecordingSink
import mayday.agents.AgentAction
import mayday.agents.AgentInput
import mayday.agents.Delivery
import mayday.agents.LlmInput
import mayday.agents.SubjectKind
import mayday.api.v1.AuditServiceGrpcKt
import mayday.api.v1.AuthServiceGrpcKt
import mayday.api.v1.Enforcement
import mayday.api.v1.IssueTokenRequest
import mayday.api.v1.PolicyServiceGrpcKt
import mayday.api.v1.Scope
import mayday.api.v1.SetAgentAdminRequest
import mayday.api.v1.SetSettingRequest
import mayday.api.v1.TokenScope as PTokenScope
import mayday.api.v1.UserServiceGrpcKt
import mayday.audit.AuditCategory
import mayday.audit.AuditChain
import mayday.audit.AuditOutcome
import mayday.audit.AuditRecord
import mayday.eventually
import mayday.identity.TokenScope
import mayday.incidents.NewIncident
import mayday.policy.Keys
import mayday.shared.Severity
import mayday.suspendTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audit stream as an org admin's SIEM sees it: every API, MCP and tool call with actor and on-behalf-of,
 * token, policy and opt-in events, and a hash chain that verifies end to end.
 */
@TestPropertySource(properties = ["spring.grpc.server.inprocess.name=audit-stream-contract"])
class AuditStreamContractTest : ServerContract() {
    @Autowired private lateinit var sink: RecordingSink

    private fun unique() = UUID.randomUUID().toString().take(8)

    private suspend fun await(what: String, match: (AuditRecord) -> Boolean): AuditRecord {
        var found: AuditRecord? = null
        eventually(5000, "no audit record: $what") {
            app.audit.flush()
            found = sink.records.lastOrNull(match)
            found != null
        }
        return found!!
    }

    private fun scoped(user: String, vararg scopes: TokenScope) = runBlocking { app.identity.issueToken(principal(user), "t", scopes.toSet(), null).secret }

    @Test
    fun `a denied gRPC call is recorded with the caller, credential, source and reason`() = suspendTest {
        val key = "INC${unique().uppercase().take(4)}"
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            PolicyServiceGrpcKt.PolicyServiceCoroutineStub(channel).setSetting(SetSettingRequest.newBuilder()
                .setScope(Scope.SCOPE_ORG).setKey(Keys.INCIDENT_PREFIX).setValue(key).setEnforcement(Enforcement.ENFORCEMENT_NONE).build(), auth("member"))
        }
        val r = await("denied SetSetting") { it.action == "grpc:PolicyService/SetSetting" && it.actor.email == "member@example.com" }
        assertEquals(AuditCategory.AUTHORIZATION, r.category)
        assertEquals(AuditOutcome.DENIED, r.outcome)
        assertEquals("forbidden", r.reason)
        assertEquals("api_token", r.credential)
        assertEquals("grpc", r.source.channel)
        assertEquals("127.0.0.1", r.source.ip)
        assertEquals(Keys.INCIDENT_PREFIX, r.details["req.key"])
        assertNull(r.onBehalfOf)
    }

    @Test
    fun `a call without valid credentials is recorded as anonymous and denied`() = suspendTest {
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") {
            AuthServiceGrpcKt.AuthServiceCoroutineStub(channel).getMe(Empty.getDefaultInstance(), bearer("mdy_not-a-token"))
        }
        val r = await("anonymous GetMe") { it.action == "grpc:AuthService/GetMe" && it.outcome == AuditOutcome.DENIED }
        assertEquals("anonymous", r.actor.type)
        assertEquals("api_token", r.credential)
        assertEquals("unauthenticated", r.reason)
    }

    @Test
    fun `token issue and revoke are recorded without the secret`() = suspendTest {
        val name = "ci-${unique()}"
        val users = UserServiceGrpcKt.UserServiceCoroutineStub(channel)
        val issued = users.issueToken(IssueTokenRequest.newBuilder().setName(name).addScopes(PTokenScope.TOKEN_SCOPE_READ)
            .addScopes(PTokenScope.TOKEN_SCOPE_ADMIN).build(), auth("member"))
        val r = await("token.issued") { it.action == "token.issued" && it.target?.name == name }
        assertEquals(AuditCategory.TOKEN, r.category)
        assertEquals("member@example.com", r.actor.email)
        assertEquals("read", r.details["scopes"], "a member does not get the admin scope")
        assertEquals("admin,read", r.details["requested"])
        assertEquals(issued.secret.take(10), r.details["prefix"])
        users.revokeToken(mayday.api.v1.IdRequest.newBuilder().setId(issued.token.id).build(), auth("member"))
        await("token.revoked") { it.action == "token.revoked" && it.target?.id == issued.token.id }
        app.audit.flush()
        assertTrue(sink.lines.none { issued.secret in it }, "the token secret never reaches the stream")
    }

    @Test
    fun `policy changes carry the old and the new value`() = suspendTest {
        val stub = PolicyServiceGrpcKt.PolicyServiceCoroutineStub(channel)
        fun req(v: String) = SetSettingRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId(team.id).setKey(Keys.POSTMORTEM_DUE_DAYS)
            .setValue(v).setEnforcement(Enforcement.ENFORCEMENT_NONE).build()
        stub.setSetting(req("5"), auth("lead"))
        stub.setSetting(req("3"), auth("lead"))
        val r = await("setting.set") { it.action == "setting.set" && it.details["value"] == "3" && it.details["scopeId"] == team.id }
        assertEquals(AuditCategory.POLICY, r.category)
        assertEquals("5", r.details["previous"])
        assertEquals("lead@example.com", r.actor.email)
        assertEquals(listOf(team.id), r.teams)
    }

    @Test
    fun `admin opt-in for agents is recorded`() = suspendTest {
        app.policy.set(principal("admin"), mayday.policy.Scope.ORG, "org", Keys.AGENT_EXCHANGE_ADMIN, "true", mayday.policy.Enforcement.NONE)
        UserServiceGrpcKt.UserServiceCoroutineStub(channel).setAgentAdmin(SetAgentAdminRequest.newBuilder().setAllow(true).build(), auth("admin"))
        val r = await("agent_admin.enabled") { it.action == "agent_admin.enabled" }
        assertEquals("admin@example.com", r.actor.email)
        app.policy.set(principal("admin"), mayday.policy.Scope.ORG, "org", Keys.AGENT_EXCHANGE_ADMIN, "false", mayday.policy.Enforcement.NONE)
        val cleared = await("agent_admin.cleared") { it.action == "agent_admin.cleared" }
        assertEquals("system", cleared.actor.type)
        assertTrue(userOf("admin").id in cleared.details.getValue("users"))
    }

    @Test
    fun `an agent acting on behalf of a person is recorded with both identities on every tool call`() = suspendTest {
        val creds = app.agents.create(principal("admin"), AgentInput("Auditor ${unique()}", delivery = Delivery.PULL, actions = setOf(AgentAction.ANALYZE)))
        await("agent.created") { it.action == "agent.created" && it.target?.id == creds.agent.id }
        await("agent token issued") { it.action == "token.issued" && it.details["owner"] == "agent:${creds.agent.id}" }
        val user = scoped("member", TokenScope.READ, TokenScope.WRITE)
        val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=$user&actor_token=${creds.token!!.secret}"
        val delegated = json(send("POST", "/oauth/token", form, contentType = "application/x-www-form-urlencoded")).jsonObject["access_token"]!!.jsonPrimitive.content
        val ex = await("token.exchanged") { it.action == "token.exchanged" && it.actor.id == creds.agent.id }
        assertEquals("member@example.com", ex.onBehalfOf?.email)
        assertEquals("false", ex.details["adminKept"])

        val headers = mapOf("Authorization" to "Bearer $delegated", "User-Agent" to "coding-agent/1.0")
        val ok = send("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_incidents","arguments":{"team":"checkout","title":"secret text"}}}""", headers)
        assertEquals(200, ok.statusCode(), ok.body())
        val call = await("tool call") { it.action == "tool:list_incidents" && it.actor.id == creds.agent.id }
        assertEquals(AuditOutcome.SUCCESS, call.outcome)
        assertEquals("member@example.com", call.onBehalfOf?.email)
        assertEquals("delegated_token", call.credential)
        assertEquals("mcp", call.source.channel)
        assertEquals("coding-agent/1.0", call.source.userAgent)
        assertEquals("checkout", call.details["arg.team"])
        assertFalse(call.details.values.any { "secret text" in it }, "free text arguments stay out of the stream")

        send("POST", "/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"set_user_role","arguments":{"user_id":"u1","role":"ADMIN"}}}""", headers)
        val refused = await("refused tool") { it.action == "tool:set_user_role" && it.actor.id == creds.agent.id }
        assertEquals(AuditOutcome.DENIED, refused.outcome)
        assertEquals("not_allowed", refused.reason)
        assertEquals("member@example.com", refused.onBehalfOf?.email)
        assertEquals("ADMIN", refused.details["arg.role"])
    }

    @Test
    fun `a person starts the built-in agent and its tool calls name the person and the run`() = suspendTest {
        app.agents.updateLlm(principal("admin"), LlmInput(apiKey = "sk-test", enabled = true))
        val inc = app.incidents.declare(principal("member"), NewIncident("Audit ${unique()}", severity = Severity.SEV3, teamIds = listOf(team.id)))
        llm.toolCall("get_incident", kotlinx.serialization.json.buildJsonObject { put("ref", kotlinx.serialization.json.JsonPrimitive(inc.key)) })
        llm.answer("Looked at it.")
        val run = app.agents.trigger(principal("member"), AgentAction.ANALYZE, SubjectKind.INCIDENT, inc.key, null, "")
        val started = await("run.started") { it.action == "run.started" && it.target?.id == run.id }
        assertEquals("member@example.com", started.actor.email)
        val tool = await("internal tool call") { it.action == "tool:get_incident" && it.runId == run.id }
        assertEquals("internal-agent", tool.source.channel)
        assertEquals("agent", tool.actor.type)
        assertEquals("member@example.com", tool.onBehalfOf?.email)
        assertEquals(inc.key, tool.details["arg.ref"])
        await("run.succeeded") { it.action == "run.succeeded" && it.target?.id == run.id }
    }

    @Test
    fun `HTTP intake and sign-in are recorded`() = suspendTest {
        val denied = send("POST", "/api/v1/alerts", """{"title":"x","team":"checkout"}""", mapOf("Authorization" to "Bearer ${scoped("member", TokenScope.READ)}"))
        assertEquals(403, denied.statusCode())
        val r = await("denied intake") { it.action == "http:POST /api/v1/alerts" && it.outcome == AuditOutcome.DENIED }
        assertEquals("forbidden", r.reason)
        assertEquals("member@example.com", r.actor.email)
        assertEquals("http", r.source.channel)

        login("viewer@example.com")
        val s = await("sign_in") { it.action == "sign_in" && it.actor.email == "viewer@example.com" }
        assertEquals(AuditCategory.AUTHENTICATION, s.category)
        assertEquals("dev_login", s.details["method"])
        assertEquals("127.0.0.1", s.source.ip)
    }

    @Test
    fun `gRPC-Web calls are recorded with the browser address and user agent`() = suspendTest {
        val r = send("POST", "/mayday.v1.AuthService/WhoAmI", "\u0000\u0000\u0000\u0000\u0000", mapOf(
            "Authorization" to "Bearer ${token("viewer")}", "User-Agent" to "Browser/1", "X-Forwarded-For" to "203.0.113.9",
        ), contentType = "application/grpc-web+proto")
        assertEquals(200, r.statusCode())
        val rec = await("grpc-web WhoAmI") { it.action == "grpc:AuthService/WhoAmI" && it.source.channel == "grpc-web" && it.actor.email == "viewer@example.com" }
        assertEquals("127.0.0.1", rec.source.ip)
        assertEquals("203.0.113.9", rec.source.forwardedFor)
        assertEquals("Browser/1", rec.source.userAgent)
    }

    @Test
    fun `network gRPC clients cannot spoof the gRPC-Web source headers`() = suspendTest {
        val md = auth("viewer").apply {
            put(io.grpc.Metadata.Key.of("x-mayday-channel", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "grpc-web")
            put(io.grpc.Metadata.Key.of("x-mayday-client-ip", io.grpc.Metadata.ASCII_STRING_MARSHALLER), "10.9.9.9")
        }
        AuthServiceGrpcKt.AuthServiceCoroutineStub(channel).whoAmI(Empty.getDefaultInstance(), md)
        val rec = await("spoofed WhoAmI") { it.action == "grpc:AuthService/WhoAmI" && it.source.channel == "grpc" && it.actor.email == "viewer@example.com" }
        assertEquals("127.0.0.1", rec.source.ip)
    }

    @Test
    fun `a client that closes its live stream is not a failure`() = suspendTest {
        val stream = mayday.api.v1.EventServiceGrpcKt.EventServiceCoroutineStub(channel)
            .subscribe(mayday.api.v1.SubscribeRequest.getDefaultInstance(), auth("lead"))
        stream.first()
        val r = await("closed Subscribe") { it.action == "grpc:EventService/Subscribe" && it.actor.email == "lead@example.com" }
        assertEquals(AuditOutcome.SUCCESS, r.outcome)
        assertEquals("cancelled", r.details["status"])
        assertNull(r.reason)
    }

    @Test
    fun `only administrators read the stream status`() = suspendTest {
        val stub = AuditServiceGrpcKt.AuditServiceCoroutineStub(channel)
        val status = stub.getAuditStatus(Empty.getDefaultInstance(), auth("admin"))
        assertEquals("mayday.audit.v1", status.schema)
        assertEquals(listOf("recording"), status.sinksList.map { it.name })
        assertTrue(status.accepted > 0)
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { stub.getAuditStatus(Empty.getDefaultInstance(), auth("member")) }
    }

    @Test
    fun `the whole stream verifies`() = suspendTest {
        AuthServiceGrpcKt.AuthServiceCoroutineStub(channel).whoAmI(Empty.getDefaultInstance(), auth("member"))
        await("any call") { it.action == "grpc:AuthService/WhoAmI" }
        val report = AuditChain.verify(sink.lines.asSequence(), AuditChain.key("contract-test-secret"))
        assertTrue(report.ok, report.problems.take(5).toString())
        assertNotNull(report.instances.keys.singleOrNull())
        assertEquals(1L, report.instances.values.single().first)
    }
}
