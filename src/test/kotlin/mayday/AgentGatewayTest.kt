package mayday

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mayday.agents.AgentAction
import mayday.agents.AuthStyle
import mayday.agents.BudgetExhausted
import mayday.agents.CredentialMode
import mayday.agents.GatewayAuth
import mayday.agents.INTERNAL_AGENT_ID
import mayday.agents.LlmEnvironment
import mayday.agents.LlmInput
import mayday.agents.RunStatus
import mayday.agents.StaticCredential
import mayday.agents.SubjectKind
import mayday.agents.ToolCatalog
import mayday.agents.ToolOutcome
import mayday.agents.ToolSpec
import mayday.agents.parseRetryAfter
import mayday.agents.OAuthGrant
import mayday.agents.statedCooldown
import mayday.agents.DEFAULT_COOLDOWN
import mayday.alerting.AlertSignal
import mayday.identity.Principal
import mayday.incidents.IncidentStatus
import mayday.incidents.NewIncident
import mayday.shared.Invalid
import mayday.shared.Severity
import mayday.shared.Unavailable
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agent is an add-on: a missing, slow or out-of-budget agent never blocks alerting or incidents. */
class AgentIndependenceTest {
    @Test
    fun `alerting and incidents work with no agent configured`() = suspendTest {
        val f = Fixture()
        assertFalse(f.app.agents.internalReady())
        val r = f.app.alerts.ingest(f.member, AlertSignal("Checkout errors", Severity.SEV1, "checkout"))
        assertNotNull(r.incidentKey, "a SEV1 alert opens an incident without an agent")
        val inc = f.app.incidents.get(f.viewer, r.incidentKey!!)
        f.app.incidents.update(f.member, inc.key, mayday.incidents.IncidentPatch(status = IncidentStatus.RESOLVED))
        assertEquals(IncidentStatus.RESOLVED, f.app.incidents.get(f.viewer, inc.key).status)
        val e = assertFailsWith<Unavailable> { f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, inc.key, null, "") }
        assertTrue(e.message!!.contains("Configure"))
        delay(100)
        assertTrue(f.store.runs.recent(10).isEmpty(), "no agent run starts when no agent is configured")
    }

    @Test
    fun `alert ingest does not wait for a hanging agent`() = suspendTest {
        val f = Fixture()
        f.enableLlm()
        f.llm.gate = CompletableDeferred()
        val r = withTimeout(2000) { f.app.alerts.ingest(f.member, AlertSignal("Database down", Severity.SEV1, "checkout")) }
        assertNotNull(r.incidentKey)
        eventually { f.llm.requests.isNotEmpty() }
        val run = f.store.runs.recent(10).single()
        assertEquals(RunStatus.RUNNING, run.status, "the agent is still busy, the incident exists already")
        f.llm.gate!!.complete(Unit)
        assertEquals(RunStatus.SUCCEEDED, f.awaitRun(run.id).status)
    }

    @Test
    fun `alert ingest succeeds when the agent fails`() = suspendTest {
        val f = Fixture()
        f.enableLlm()
        f.llm.fail(IllegalStateException("gateway down"))
        val r = f.app.alerts.ingest(f.member, AlertSignal("Queue backlog", Severity.SEV1, "checkout"))
        assertNotNull(r.incidentKey)
        eventually { f.store.runs.recent(10).any { it.status.terminal } }
        assertEquals(RunStatus.FAILED, f.store.runs.recent(10).single().status)
    }
}

class BudgetExhaustedTest {
    private val f = Fixture()
    private suspend fun incident() = f.app.incidents.declare(f.member, NewIncident("Checkout down", severity = Severity.SEV3, teamIds = listOf(f.team.id)))

    @Test
    fun `HTTP 429 fails the run once, without retry, and pauses the agent`() = suspendTest {
        f.enableLlm()
        val i = incident()
        f.llm.fail(BudgetExhausted(Duration.ofMinutes(10)))
        val run = f.awaitRun(f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "").id)
        assertEquals(RunStatus.FAILED, run.status)
        assertTrue(run.error.contains("HTTP 429"), run.error)
        assertTrue(run.error.contains("does not retry"), run.error)
        assertEquals(1, f.llm.requests.size, "exactly one call reaches the gateway")

        val view = f.app.agents.llm(f.viewer)
        val until = assertNotNull(view.pausedUntil)
        assertTrue(until.isAfter(Instant.now().plus(Duration.ofMinutes(9))) && until.isBefore(Instant.now().plus(Duration.ofMinutes(11))))
        assertTrue(view.pauseReason.contains("429"))

        val e = assertFailsWith<Unavailable> { f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "") }
        assertTrue(e.message!!.contains("budget"))
        assertEquals(1, f.llm.requests.size, "a paused agent makes no call")
    }

    @Test
    fun `a paused agent skips automatic triggers and alerting goes on`() = suspendTest {
        f.enableLlm()
        f.llm.fail(BudgetExhausted(null))
        val first = f.app.alerts.ingest(f.member, AlertSignal("Errors", Severity.SEV2, "checkout"))
        assertNotNull(first.incidentKey)
        eventually { f.store.runs.recent(10).any { it.status == RunStatus.FAILED } }
        val pausedUntil = assertNotNull(f.app.agents.llm(f.viewer).pausedUntil)
        assertTrue(pausedUntil.isAfter(Instant.now().plus(Duration.ofMinutes(14))), "default pause without Retry-After is 15 minutes")

        val storm = (1..20).map { f.app.alerts.ingest(f.member, AlertSignal("Storm $it", Severity.SEV2, "checkout")) }
        assertTrue(storm.all { it.incidentKey != null }, "every alert still opens its incident")
        delay(200)
        assertEquals(1, f.store.runs.recent(50).size, "no new runs while paused")
        assertEquals(1, f.llm.requests.size)
    }

    @Test
    fun `changing the LLM settings resumes a paused agent`() = suspendTest {
        f.enableLlm()
        val i = incident()
        f.llm.fail(BudgetExhausted(Duration.ofHours(1)))
        f.awaitRun(f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "").id)
        assertNotNull(f.app.agents.llm(f.viewer).pausedUntil)
        f.app.agents.updateLlm(f.admin, LlmInput(enabled = true))
        assertNull(f.app.agents.llm(f.viewer).pausedUntil)
        f.llm.answer("Analysis done")
        assertEquals(RunStatus.SUCCEEDED, f.awaitRun(f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "").id).status)
    }

    @Test
    fun `a 429 from a tool goes back to the model as a tool error`() = suspendTest {
        val tool = object : ToolCatalog {
            override suspend fun tools(principal: Principal, internal: Boolean) =
                listOf(ToolSpec("gw__query", "Query metrics", buildJsonObject { put("type", "object") }))
            override suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome =
                if (name == "gw__query") throw BudgetExhausted(Duration.ofSeconds(30)) else ToolOutcome("Unknown tool '$name'", true)
        }
        val g = Fixture(extraTools = listOf(tool))
        g.enableLlm()
        val i = g.app.incidents.declare(g.member, NewIncident("Latency", severity = Severity.SEV3, teamIds = listOf(g.team.id)))
        g.llm.toolCall("gw__query", buildJsonObject { })
        g.llm.answer("The metrics tool is out of budget.")
        val run = g.awaitRun(g.app.agents.trigger(g.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "").id)
        assertEquals(RunStatus.SUCCEEDED, run.status)
        val result = g.llm.requests.last().messages.flatMap { it.blocks }.filterIsInstance<mayday.agents.LlmBlock.ToolResult>().single()
        assertTrue(result.isError && result.content.contains("429"))
        assertNull(g.app.agents.llm(g.viewer).pausedUntil, "a tools gateway refusal does not pause the LLM")
    }
}

class GatewaySettingsTest {
    @Test
    fun `identity headers carry the token in the configured style plus the custom headers`() = suspendTest {
        val bearer = GatewayAuth(AuthStyle.BEARER, StaticCredential("t1"), mapOf("x-company-agent-id" to "agent-42", "x-team" to "sre"))
        assertEquals(mapOf("x-company-agent-id" to "agent-42", "x-team" to "sre", "Authorization" to "Bearer t1"), bearer.headers())
        val spoof = GatewayAuth(AuthStyle.BEARER, StaticCredential("t1"), mapOf("Authorization" to "Bearer fixed"))
        assertEquals("Bearer t1", spoof.headers()["Authorization"], "the token wins over a custom header of the same name")
        val apiKey = GatewayAuth(AuthStyle.API_KEY, StaticCredential("t2"))
        assertEquals(mapOf("x-api-key" to "t2"), apiKey.headers())
        assertEquals(emptyMap(), GatewayAuth.none.headers())
    }

    @Test
    fun `the provider decides the default auth style`() = suspendTest {
        val f = Fixture()
        f.app.agents.updateLlm(f.admin, LlmInput(provider = "openai", apiKey = "k", enabled = true, headers = mapOf("x-company-agent-id" to "a-1")))
        f.app.agents.internalReady().let(::assertTrue)
        val auth = f.app.agents.gatewayAuth()
        assertEquals(mapOf("x-company-agent-id" to "a-1", "Authorization" to "Bearer k"), auth.headers())
        f.app.agents.updateLlm(f.admin, LlmInput(provider = "anthropic"))
        assertEquals("k", f.app.agents.gatewayAuth().headers()["x-api-key"])
        f.app.agents.updateLlm(f.admin, LlmInput(authStyle = AuthStyle.BEARER))
        assertEquals("Bearer k", f.app.agents.gatewayAuth().headers()["Authorization"])
    }

    @Test
    fun `invalid settings are rejected`() = suspendTest {
        val f = Fixture()
        assertFailsWith<Invalid> { f.app.agents.updateLlm(f.admin, LlmInput(provider = "gemini")) }
        assertFailsWith<Invalid> { f.app.agents.updateLlm(f.admin, LlmInput(baseUrl = "ftp://gateway")) }
        assertFailsWith<Invalid> { f.app.agents.updateLlm(f.admin, LlmInput(headers = mapOf("x:y" to "1"))) }
        assertFailsWith<mayday.shared.Forbidden> { f.app.agents.updateLlm(f.member, LlmInput(enabled = true)) }
    }

    @Test
    fun `readiness follows the credential source`() = suspendTest {
        val f = Fixture()
        f.app.agents.updateLlm(f.admin, LlmInput(enabled = true, credential = CredentialMode.OAUTH2))
        assertFalse(f.app.agents.internalReady(), "OAuth 2.0 needs a token URL, a client ID and a secret or assertion")
        f.app.agents.updateLlm(f.admin, LlmInput(tokenUrl = "https://idp.example.com/oauth2/token", clientId = "c", assertionFile = "/var/run/token"))
        assertTrue(f.app.agents.internalReady())
        f.app.agents.updateLlm(f.admin, LlmInput(tokenUrl = "", issuer = "https://idp.example.com", clientId = "", grant = OAuthGrant.TOKEN_EXCHANGE))
        assertTrue(f.app.agents.internalReady(), "token exchange with issuer discovery needs only the subject token file")
        f.app.agents.updateLlm(f.admin, LlmInput(grant = OAuthGrant.CLIENT_CREDENTIALS))
        assertFalse(f.app.agents.internalReady(), "client credentials need a client ID")
        f.app.agents.updateLlm(f.admin, LlmInput(credential = CredentialMode.FILE))
        assertFalse(f.app.agents.internalReady())
        f.app.agents.updateLlm(f.admin, LlmInput(tokenFile = "/var/run/gateway/token"))
        assertTrue(f.app.agents.internalReady())
        f.app.agents.updateLlm(f.admin, LlmInput(credential = CredentialMode.NONE))
        assertTrue(f.app.agents.internalReady())
    }

    @Test
    fun `secrets never appear in the view`() = suspendTest {
        val f = Fixture()
        val v = f.app.agents.updateLlm(f.admin, LlmInput(apiKey = "sk-secret", clientSecret = "cs-secret"))
        assertTrue(v.hasApiKey && v.hasClientSecret)
        assertFalse(mayday.shared.JSON.encodeToString(mayday.agents.LlmView.serializer(), v).contains("secret\""))
        assertFalse(mayday.shared.JSON.encodeToString(mayday.agents.LlmView.serializer(), v).let { it.contains("sk-secret") || it.contains("cs-secret") })
    }

    @Test
    fun `environment settings override stored settings and lock them`() = suspendTest {
        val env = LlmEnvironment(
            provider = "openai", baseUrl = "https://ai-gateway.example.com/openai/v1", enabled = true,
            credential = CredentialMode.OAUTH2, tokenUrl = "https://idp.example.com/oauth2/token",
            clientId = "client", assertionFile = "/var/run/secrets/mayday/workload-identity/token",
            scope = "ai-gateway.invoke", headers = mapOf("x-company-agent-id" to "agent-7"),
        )
        val f = Fixture(env)
        val v = f.app.agents.llm(f.viewer)
        assertEquals("https://ai-gateway.example.com/openai/v1", v.baseUrl)
        assertEquals(AuthStyle.BEARER, v.authStyle)
        assertTrue(v.ready)
        assertTrue(v.managedFields.containsAll(listOf("provider", "baseUrl", "credential", "headers")))
        val e = assertFailsWith<Invalid> { f.app.agents.updateLlm(f.admin, LlmInput(baseUrl = "https://other.example.com")) }
        assertTrue(e.message!!.contains("environment"))
        f.app.agents.updateLlm(f.admin, LlmInput(model = "claude-opus-5-5"))
        assertEquals("claude-opus-5-5", f.app.agents.llm(f.viewer).model)
        assertTrue(f.app.agents.internalReady())
        assertEquals(INTERNAL_AGENT_ID, f.app.agents.eligible(AgentAction.ANALYZE).first().id)
    }

    @Test
    fun `MCP servers store the identity mode`() = suspendTest {
        val f = Fixture()
        val s = f.app.agents.saveMcpServer(f.admin, null, mayday.agents.McpServerInput("Tools gateway", "https://tools.example.com/mcp", useAgentIdentity = true))
        assertTrue(s.useAgentIdentity)
        assertTrue(f.app.agents.mcpServers(f.viewer).single().useAgentIdentity)
    }

    @Test
    fun `Retry-After accepts seconds and HTTP dates`() {
        assertEquals(Duration.ofSeconds(120), parseRetryAfter("120"))
        val now = Instant.parse("2026-11-20T10:00:00Z")
        assertEquals(Duration.ofSeconds(90), parseRetryAfter("Fri, 20 Nov 2026 10:01:30 GMT", now))
        assertNull(parseRetryAfter(""))
        assertNull(parseRetryAfter("soon"))
    }

    @Test
    fun `the stated cooldown comes from headers by precedence, then the JSON body, never from message text`() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        fun of(vararg h: Pair<String, String>, body: String? = null): Duration? {
            val map = h.toMap()
            return statedCooldown({ n -> map.entries.filter { it.key.equals(n, true) }.map { it.value } }, body, now)
        }
        assertEquals(Duration.ofSeconds(30), of("retry-after" to "30", "retry-after-ms" to "1000"))
        assertEquals(Duration.ofSeconds(1), of("Retry-After-Ms" to "1000", "RateLimit-Reset" to "50"))
        assertEquals(Duration.ofSeconds(45), of("RateLimit" to "\"default\";r=0;t=45", "RateLimit-Reset" to "50"))
        assertEquals(Duration.ofSeconds(50), of("RateLimit-Reset" to "50"))
        assertEquals(Duration.ofSeconds(20), of("X-RateLimit-Reset" to "20"))
        assertEquals(Duration.ofMinutes(5), of("X-RateLimit-Reset" to now.plusSeconds(300).epochSecond.toString()))
        assertEquals(Duration.ofMinutes(2), of("anthropic-ratelimit-tokens-reset" to "2026-11-20T10:02:00Z", "anthropic-ratelimit-requests-reset" to "2026-11-20T10:00:10Z"))
        assertEquals(Duration.ofMillis(1_500), of("x-ratelimit-reset-requests" to "1.5s"))
        assertEquals(Duration.ofMillis(250), of("x-ratelimit-reset-tokens" to "250ms"))
        assertEquals(Duration.ofSeconds(12), of(body = """{"retry_after": 12}"""))
        assertNull(of(body = """{"error":{"message":"try again in 30 seconds"}}"""))
        assertNull(of("Retry-After" to "soon"))
        assertNull(of())
        assertEquals(Duration.ofMinutes(15), DEFAULT_COOLDOWN)
    }
}

