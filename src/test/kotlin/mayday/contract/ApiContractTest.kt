package mayday.contract

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.Fixture
import mayday.adapters.auth.Sessions
import mayday.adapters.dispatch.WebhookDispatcher
import mayday.adapters.http.Api
import mayday.adapters.http.Metrics
import mayday.adapters.http.Server
import mayday.adapters.http.SseHub
import mayday.adapters.http.StaticFiles
import mayday.agents.AgentAction
import mayday.agents.AgentConfig
import mayday.agents.AgentKind
import mayday.agents.AgentRun
import mayday.agents.DispatchPayload
import mayday.agents.RunTrigger
import mayday.agents.SubjectKind
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.shared.Actor
import mayday.shared.Crypto
import mayday.shared.JSON
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** HTTP and MCP contract: status codes, error shape and payloads that clients and agents rely on. */
class ApiContractTest {
    private val f = Fixture()
    private val sse = SseHub(f.events, 100)
    private val server = Server(
        Api(f.app.identity, f.app.directory, f.app.policy, f.app.incidents, f.app.alerts, f.app.agents, f.app.reports, f.app.mcp,
            Sessions(f.app.signer, Duration.ofHours(1), false), null, true, "http://mayday.test", sse),
        StaticFiles(null), Metrics(), sse,
    ) { true }
    private val port = server.start("127.0.0.1", 0)
    private val http = HttpClient.newHttpClient()
    private val base = "http://127.0.0.1:$port"

    @AfterTest
    fun stop() = server.stop()

    private fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(base + path)).method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) b.header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(r: HttpResponse<String>) = JSON.parseToJsonElement(r.body())

    private fun login(email: String): Map<String, String> {
        val r = call("POST", "/api/v1/auth/dev-login", """{"email":"$email"}""")
        assertEquals(200, r.statusCode())
        val cookie = r.headers().firstValue("Set-Cookie").get().substringBefore(';')
        return mapOf("Cookie" to cookie, "X-Requested-With" to "mayday")
    }

    private fun bearer(secret: String) = mapOf("Authorization" to "Bearer $secret")

    @Test
    fun `errors use one JSON shape with the right status`() {
        val unauth = call("GET", "/api/v1/incidents")
        assertEquals(401, unauth.statusCode())
        assertEquals("unauthenticated", json(unauth).jsonObject["error"]!!.jsonPrimitive.content)
        assertNotNull(json(unauth).jsonObject["message"])
        val h = login("member@example.com")
        assertEquals(404, call("GET", "/api/v1/incidents/INC-999", headers = h).statusCode())
        assertEquals(400, call("POST", "/api/v1/incidents", "{}", h).statusCode())
        assertEquals(405, call("PUT", "/api/v1/incidents", "{}", h).statusCode())
        assertEquals(404, call("GET", "/api/v1/nothing", headers = h).statusCode())
    }

    @Test
    fun `cookie sessions need the anti-CSRF header for changes`() {
        val h = login("member@example.com")
        val body = """{"title":"X","severity":"SEV3","teamIds":["checkout"]}"""
        assertEquals(403, call("POST", "/api/v1/incidents", body, h - "X-Requested-With").statusCode())
        assertEquals(201, call("POST", "/api/v1/incidents", body, h).statusCode())
    }

    @Test
    fun `incident lifecycle over HTTP`() {
        val h = login("member@example.com")
        val created = json(call("POST", "/api/v1/incidents", """{"title":"Checkout down","severity":"SEV1","teamIds":["checkout"]}""", h)).jsonObject
        val key = created["key"]!!.jsonPrimitive.content
        assertEquals("INC-1", key)
        assertEquals("true", created["postmortemRequired"]!!.jsonPrimitive.content)
        val patched = call("PATCH", "/api/v1/incidents/$key", """{"status":"RESOLVED"}""", h)
        assertEquals(200, patched.statusCode())
        assertNotNull(json(patched).jsonObject["resolvedAt"])
        assertEquals(201, call("POST", "/api/v1/incidents/$key/timeline", """{"text":"Rolled back"}""", h).statusCode())
        val tl = json(call("GET", "/api/v1/incidents/$key/timeline", headers = h)).jsonArray
        assertTrue(tl.any { it.jsonObject["text"]!!.jsonPrimitive.content == "Rolled back" })
        val list = json(call("GET", "/api/v1/incidents?severity=SEV1&limit=10", headers = h)).jsonObject
        assertEquals(1, list["items"]!!.jsonArray.size)
        assertEquals(200, call("PUT", "/api/v1/incidents/$key/resolution", """{"notes":"We rolled back."}""", h).statusCode())
        val report = json(call("GET", "/api/v1/reports?team=checkout", headers = h)).jsonObject
        assertEquals(1, report["incidents"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `alertmanager webhook creates and resolves alerts`() {
        val tok = f.app.identity.issueToken(f.app.identity.principalForUser(f.memberUser), "am", setOf(mayday.identity.TokenScope.ALERTS_INGEST), null)
        val firing = """{"alerts":[{"status":"firing","fingerprint":"abc","labels":{"alertname":"HighErrors","severity":"critical","team":"checkout"},"annotations":{"summary":"5xx above 5%"}}]}"""
        val r = call("POST", "/api/v1/integrations/alertmanager", firing, bearer(tok.secret))
        assertEquals(200, r.statusCode(), r.body())
        assertEquals(1, json(r).jsonObject["created"]!!.jsonPrimitive.content.toInt())
        call("POST", "/api/v1/integrations/alertmanager", firing.replace("\"firing\"", "\"resolved\""), bearer(tok.secret))
        val alerts = json(call("GET", "/api/v1/alerts", headers = login("viewer@example.com"))).jsonObject["items"]!!.jsonArray
        assertEquals("RESOLVED", alerts.single().jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("SEV1", alerts.single().jsonObject["severity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `admin endpoints are RBAC protected`() {
        assertEquals(403, call("POST", "/api/v1/teams", """{"name":"New"}""", login("member@example.com")).statusCode())
        assertEquals(201, call("POST", "/api/v1/teams", """{"name":"New"}""", login("admin@example.com")).statusCode())
        val locked = call("PUT", "/api/v1/settings", """{"scope":"ORG","key":"${Keys.MCP_WRITE}","value":"true","enforcement":"LOCKED"}""", login("lead@example.com"))
        assertEquals(403, locked.statusCode())
    }

    @Test
    fun `locked settings answer with the locked error code`() {
        f.app.policy.set(f.admin, Scope.ORG, "org", Keys.POSTMORTEM_DUE_DAYS, "7", Enforcement.LOCKED)
        val r = call("PUT", "/api/v1/settings", """{"scope":"TEAM","scopeId":"checkout","key":"${Keys.POSTMORTEM_DUE_DAYS}","value":"14"}""", login("lead@example.com"))
        assertEquals(403, r.statusCode())
        assertEquals("locked", json(r).jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `MCP initialize, list and call follow JSON-RPC`() {
        val tok = f.app.identity.issueToken(f.app.identity.principalForUser(f.memberUser), "mcp", setOf(mayday.identity.TokenScope.READ, mayday.identity.TokenScope.WRITE), null)
        val h = bearer(tok.secret)
        val init = json(call("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""", h)).jsonObject
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(202, call("POST", "/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""", h).statusCode())
        val tools = json(call("POST", "/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", h)).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("get_incident" in names && "add_timeline_entry" in names && "set_setting" !in names)
        f.app.incidents.declare(f.member, mayday.incidents.NewIncident("X", severity = mayday.shared.Severity.SEV3, teamIds = listOf(f.team.id)))
        val denied = json(call("POST", "/mcp", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add_timeline_entry","arguments":{"ref":"INC-1","text":"hi"}}}""", h)).jsonObject["result"]!!.jsonObject
        assertEquals("true", denied["isError"]!!.jsonPrimitive.content, "write access is off by default")
        f.app.policy.set(f.admin, Scope.ORG, "org", Keys.MCP_WRITE, "true", Enforcement.NONE)
        val okCall = json(call("POST", "/mcp", """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"add_timeline_entry","arguments":{"ref":"INC-1","text":"hi"}}}""", h)).jsonObject["result"]!!.jsonObject
        assertEquals("false", okCall["isError"]!!.jsonPrimitive.content)
        val unknown = json(call("POST", "/mcp", """{"jsonrpc":"2.0","id":5,"method":"nope"}""", h)).jsonObject
        assertEquals(-32601, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val batch = json(call("POST", "/mcp", """[{"jsonrpc":"2.0","id":6,"method":"ping"},{"jsonrpc":"2.0","id":7,"method":"ping"}]""", h))
        assertEquals(2, (batch as JsonArray).size)
    }

    @Test
    fun `live events stream domain changes`() {
        val h = login("member@example.com")
        val req = HttpRequest.newBuilder(URI("$base/api/v1/events")).header("Cookie", h.getValue("Cookie")).GET().build()
        val lines = CompletableFuture<String>()
        http.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).thenAccept { resp ->
            resp.body().filter { it.startsWith("event: incident.created") }.findFirst().ifPresent { lines.complete(it) }
        }
        val end = System.currentTimeMillis() + 3000
        while (sse.clients.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        call("POST", "/api/v1/incidents", """{"title":"Live","severity":"SEV3","teamIds":["checkout"]}""", h)
        assertEquals("event: incident.created", lines.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `token exchange returns a delegated token`() {
        val creds = f.app.agents.create(f.admin, mayday.agents.AgentInput("Bot", delivery = mayday.agents.Delivery.PULL, actions = setOf(AgentAction.ANALYZE)))
        val user = f.app.identity.issueToken(f.app.identity.principalForUser(f.memberUser), "u", setOf(mayday.identity.TokenScope.READ, mayday.identity.TokenScope.WRITE), null)
        val form = "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=${user.secret}&actor_token=${creds.token!!.secret}"
        val r = http.send(HttpRequest.newBuilder(URI("$base/oauth/token")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode(), r.body())
        val token = json(r).jsonObject["access_token"]!!.jsonPrimitive.content
        val who = json(call("GET", "/api/v1/whoami", headers = bearer(token))).jsonObject
        assertEquals("Bot", who["actor"]!!.jsonPrimitive.content)
        assertEquals("member@example.com", who["onBehalfOf"]!!.jsonPrimitive.content)
    }
}

/** Contract for push agents: what a receiver gets and how it verifies it. */
class WebhookContractTest {
    @Test
    fun `webhook body is signed with the agent secret and timestamp`() {
        val received = CompletableFuture<Triple<String, String, String>>()
        val hook = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        hook.createContext("/hook") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            received.complete(Triple(body, ex.requestHeaders.getFirst("X-Mayday-Timestamp"), ex.requestHeaders.getFirst("X-Mayday-Signature")))
            ex.sendResponseHeaders(204, -1); ex.close()
        }
        hook.start()
        try {
            val now = Instant.now()
            val agent = AgentConfig("a1", "Agent", kind = AgentKind.EXTERNAL, endpointUrl = "http://127.0.0.1:${hook.address.port}/hook",
                actions = setOf(AgentAction.ANALYZE), createdAt = now, updatedAt = now)
            val run = AgentRun("r1", "a1", "Agent", AgentAction.ANALYZE, SubjectKind.INCIDENT, "i1", "INC-1", trigger = RunTrigger.MANUAL, requestedBy = Actor.system, createdAt = now)
            WebhookDispatcher().dispatch(agent, "s3cret", DispatchPayload("agent_run.requested", run, "http://m", "http://m/mcp", "tok", JsonObject(emptyMap())))
            val (body, ts, sig) = received.get(5, TimeUnit.SECONDS)
            assertEquals("sha256=" + Crypto.hmacHex("s3cret", "$ts.$body"), sig)
            val parsed = JSON.parseToJsonElement(body).jsonObject
            assertEquals("tok", parsed["delegatedToken"]!!.jsonPrimitive.content)
            assertEquals("r1", parsed["run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        } finally {
            hook.stop(0)
        }
    }
}
