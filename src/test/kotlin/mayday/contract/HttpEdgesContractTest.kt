package mayday.contract

import com.google.protobuf.Empty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.adapters.http.WebFilters
import mayday.agents.AgentAction
import mayday.agents.AgentInput
import mayday.agents.Delivery
import mayday.alerting.AlertFilter
import mayday.alerting.AlertStatus
import mayday.api.v1.AuthServiceGrpcKt
import mayday.api.v1.SetAgentAdminRequest
import mayday.api.v1.UserServiceGrpcKt
import mayday.identity.TokenScope
import mayday.incidents.NewIncident
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.shared.Severity
import mayday.suspendTest
import org.springframework.test.context.TestPropertySource
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plain HTTP edges: browser sign-in, token exchange, MCP, alert intake, static files and operations endpoints. */
@TestPropertySource(properties = ["spring.grpc.server.inprocess.name=http-edges-contract"])
class HttpEdgesContractTest : ServerContract() {
    private fun unique() = UUID.randomUUID().toString().take(8)
    private fun bearerHeader(secret: String) = mapOf("Authorization" to "Bearer $secret")
    private fun scoped(user: String, vararg scopes: TokenScope) = runBlocking { app.identity.issueToken(principal(user), "t", scopes.toSet(), null).secret }

    private fun assertError(r: HttpResponse<String>, status: Int, code: String) {
        assertEquals(status, r.statusCode(), r.body())
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("application/json"))
        val body = json(r).jsonObject
        assertEquals(code, body["error"]!!.jsonPrimitive.content)
        assertTrue(body["message"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(setOf("error", "message"), body.keys)
    }

    // ---- sign-in ----

    @Test
    fun `errors use one JSON shape with the matching status`() {
        assertError(send("POST", "/api/v1/alerts", """{"title":"x","team":"checkout"}"""), 401, "unauthenticated")
        assertError(send("POST", "/auth/dev-login", ""), 400, "invalid")
        assertError(send("POST", "/auth/dev-login", "{not json"), 400, "invalid")
        assertError(send("GET", "/auth/login"), 503, "unavailable")
        assertError(send("GET", "/auth/callback?code=x&state=y"), 503, "unavailable")
        assertError(send("POST", "/api/v1/alerts", """{"title":"x","team":"nope"}""", bearerHeader(token("member"))), 404, "not_found")
        assertError(send("POST", "/api/v1/alerts", """{"title":"x","team":"checkout"}""", bearerHeader(scoped("member", TokenScope.READ))), 403, "forbidden")
        assertError(send("PUT", "/api/v1/alerts", "{}", bearerHeader(token("member"))), 405, "method_not_allowed")
        assertError(send("GET", "/api/v1/nothing", headers = bearerHeader(token("member"))), 404, "not_found")
        val get = send("GET", "/mcp", headers = bearerHeader(token("member")))
        assertError(get, 405, "method_not_allowed")
        assertEquals("POST", get.headers().firstValue("Allow").get())
    }

    @Test
    fun `dev login sets an HttpOnly session cookie that authenticates`() = suspendTest {
        val r = send("POST", "/auth/dev-login", """{"email":"Member@Example.com","name":"M"}""")
        assertEquals(200, r.statusCode())
        assertEquals("member@example.com", json(r).jsonObject["email"]!!.jsonPrimitive.content)
        val cookie = r.headers().firstValue("Set-Cookie").get()
        assertTrue(cookie.startsWith("mayday_session=") && "HttpOnly" in cookie && "SameSite=Lax" in cookie && "Path=/" in cookie, cookie)
        assertFalse("Secure" in cookie, "plain-HTTP base URLs do not get Secure cookies")
        val me = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
            .getMe(Empty.getDefaultInstance(), io.grpc.Metadata().apply { put(mayday.adapters.grpc.Grpc.COOKIE, cookie.substringBefore(';')) })
        assertEquals("member@example.com", me.user.email)
    }

    @Test
    fun `dev login links redirect only to local paths`() {
        val ok = send("GET", "/auth/dev-login?email=viewer@example.com&redirect=/incidents/INC-1")
        assertEquals(302, ok.statusCode())
        assertEquals("/incidents/INC-1", ok.headers().firstValue("Location").get())
        assertTrue(ok.headers().firstValue("Set-Cookie").get().startsWith("mayday_session="))
        assertEquals("/", send("GET", "/auth/dev-login?email=viewer@example.com&redirect=//evil.example.com").headers().firstValue("Location").get())
        assertEquals("/", send("GET", "/auth/dev-login?email=viewer@example.com&redirect=https://evil.example.com").headers().firstValue("Location").get())
        assertError(send("GET", "/auth/dev-login"), 400, "invalid")
    }

    @Test
    fun `logout clears the session cookie`() {
        val r = send("POST", "/auth/logout", headers = mapOf("Cookie" to login("member@example.com")))
        assertEquals(200, r.statusCode())
        assertEquals("/", json(r).jsonObject["logoutUrl"]!!.jsonPrimitive.content)
        val cookie = r.headers().firstValue("Set-Cookie").get()
        assertTrue(cookie.startsWith("mayday_session=;") && "Max-Age=0" in cookie, cookie)
    }

    // ---- token exchange ----

    @Test
    fun `token exchange returns a delegated token`() = suspendTest {
        val creds = app.agents.create(principal("admin"), AgentInput("Exchanger ${unique()}", delivery = Delivery.PULL, actions = setOf(AgentAction.ANALYZE)))
        val user = scoped("member", TokenScope.READ, TokenScope.WRITE)
        val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=$user&actor_token=${creds.token!!.secret}"
        val r = send("POST", "/oauth/token", form, contentType = "application/x-www-form-urlencoded")
        assertEquals(200, r.statusCode(), r.body())
        val body = json(r).jsonObject
        assertEquals("Bearer", body["token_type"]!!.jsonPrimitive.content)
        assertEquals("urn:ietf:params:oauth:token-type:access_token", body["issued_token_type"]!!.jsonPrimitive.content)
        assertEquals(3600, body["expires_in"]!!.jsonPrimitive.int)
        val who = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel).whoAmI(Empty.getDefaultInstance(), bearer(body["access_token"]!!.jsonPrimitive.content))
        assertEquals(creds.agent.name, who.actor)
        assertEquals("member@example.com", who.onBehalfOf)
        assertEquals("READ WRITE", who.scopes)

        assertError(send("POST", "/oauth/token", "grant_type=password", contentType = "application/x-www-form-urlencoded"), 400, "invalid")
        assertError(send("POST", "/oauth/token", form.replace("actor_token=", "actor_token=x").replace(creds.token!!.secret, user),
            contentType = "application/x-www-form-urlencoded"), 401, "unauthenticated")
        val swapped = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=$user&actor_token=$user"
        assertError(send("POST", "/oauth/token", swapped, contentType = "application/x-www-form-urlencoded"), 403, "forbidden")
        assertError(send("POST", "/oauth/token", "{}"), 415, "invalid")
    }

    @Test
    fun `token exchange keeps the admin scope only with the organization and the user setting`() = suspendTest {
        val creds = app.agents.create(principal("admin"), AgentInput("Admin exchanger ${unique()}", delivery = Delivery.PULL, actions = setOf(AgentAction.ANALYZE)))
        val adminToken = token("admin")
        fun exchange(): String {
            val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=$adminToken&actor_token=${creds.token!!.secret}"
            val r = send("POST", "/oauth/token", form, contentType = "application/x-www-form-urlencoded")
            assertEquals(200, r.statusCode(), r.body())
            return json(r).jsonObject["access_token"]!!.jsonPrimitive.content
        }
        val authStub = AuthServiceGrpcKt.AuthServiceCoroutineStub(channel)
        val userStub = UserServiceGrpcKt.UserServiceCoroutineStub(channel)
        val optIn = SetAgentAdminRequest.newBuilder().setAllow(true).build()
        try {
            assertFalse("ADMIN" in authStub.whoAmI(Empty.getDefaultInstance(), bearer(exchange())).scopes)
            assertStatus(io.grpc.Status.Code.PERMISSION_DENIED, "forbidden") { userStub.setAgentAdmin(optIn, bearer(adminToken)) }

            app.policy.set(principal("admin"), Scope.ORG, "org", Keys.AGENT_EXCHANGE_ADMIN, "true", Enforcement.NONE)
            assertTrue(authStub.getMe(Empty.getDefaultInstance(), bearer(adminToken)).agentAdminOrgAllowed)
            val delegated = exchange()
            assertStatus(io.grpc.Status.Code.PERMISSION_DENIED, "forbidden") { userStub.setAgentAdmin(optIn, bearer(delegated)) }
            assertTrue(userStub.setAgentAdmin(optIn, bearer(adminToken)).allowAgentAdmin)
            assertTrue("ADMIN" in authStub.whoAmI(Empty.getDefaultInstance(), bearer(exchange())).scopes)
        } finally {
            userStub.setAgentAdmin(SetAgentAdminRequest.newBuilder().setAllow(false).build(), bearer(adminToken))
            app.policy.set(principal("admin"), Scope.ORG, "org", Keys.AGENT_EXCHANGE_ADMIN, "false", Enforcement.NONE)
        }
    }

    // ---- MCP ----

    private fun mcp(body: String, headers: Map<String, String>) = send("POST", "/mcp", body, headers)
    private fun rpc(id: Int, method: String, params: String = "{}") = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""
    private fun toolNames(headers: Map<String, String>) =
        json(mcp(rpc(1, "tools/list"), headers)).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    @Test
    fun `MCP initialize, list and call follow JSON-RPC and RBAC`() = suspendTest {
        val member = bearerHeader(scoped("member", TokenScope.READ, TokenScope.WRITE))
        val init = json(mcp(rpc(1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}"""), member)).jsonObject
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("mayday", init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(202, mcp("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", member).statusCode())

        val memberTools = toolNames(member)
        assertTrue("get_incident" in memberTools && "add_timeline_entry" in memberTools && "set_setting" !in memberTools, memberTools.toString())
        val viewerTools = toolNames(bearerHeader(token("viewer")))
        assertTrue("get_incident" in viewerTools && "add_timeline_entry" !in viewerTools, viewerTools.toString())
        assertTrue("set_setting" in toolNames(bearerHeader(token("admin"))))

        val i = app.incidents.declare(principal("member"), NewIncident("MCP ${unique()}", severity = Severity.SEV4, teamIds = listOf("checkout")))
        val call = rpc(3, "tools/call", """{"name":"add_timeline_entry","arguments":{"ref":"${i.key}","text":"via mcp"}}""")
        val denied = json(mcp(call, member)).jsonObject["result"]!!.jsonObject
        assertEquals("true", denied["isError"]!!.jsonPrimitive.content, "write access is off by default")
        app.policy.set(principal("admin"), Scope.ORG, "org", Keys.MCP_WRITE, "true", Enforcement.NONE)
        try {
            val ok = json(mcp(call, member)).jsonObject["result"]!!.jsonObject
            assertEquals("false", ok["isError"]!!.jsonPrimitive.content, ok.toString())
            assertTrue(app.incidents.timeline(principal("viewer"), i.key).any { it.text == "via mcp" })
            val viewerCall = json(mcp(call, bearerHeader(token("viewer")))).jsonObject
            assertEquals(-32602, viewerCall["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        } finally {
            app.policy.clear(principal("admin"), Scope.ORG, "org", Keys.MCP_WRITE)
        }

        val read = json(mcp(rpc(4, "tools/call", """{"name":"get_incident","arguments":{"ref":"${i.key}"}}"""), bearerHeader(token("viewer")))).jsonObject["result"]!!.jsonObject
        assertEquals("false", read["isError"]!!.jsonPrimitive.content)
        assertTrue(read["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.contains(i.key))
        assertEquals(-32601, json(mcp(rpc(5, "nope"), member)).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        val batch = json(mcp("""[{"jsonrpc":"2.0","id":6,"method":"ping"},{"jsonrpc":"2.0","id":7,"method":"ping"}]""", member))
        assertEquals(2, (batch as JsonArray).size)
        assertError(mcp("{oops", member), 400, "invalid")
        assertError(mcp(rpc(8, "ping"), emptyMap()), 401, "unauthenticated")
    }

    @Test
    fun `cookie sessions need X-Requested-With for changes`() {
        val cookie = mapOf("Cookie" to login("member@example.com"))
        val body = """{"title":"CSRF ${unique()}","severity":"SEV4","team":"checkout"}"""
        assertError(send("POST", "/api/v1/alerts", body, cookie), 403, "forbidden")
        assertError(mcp(rpc(1, "ping"), cookie), 403, "forbidden")
        assertEquals(201, send("POST", "/api/v1/alerts", body, cookie + ("X-Requested-With" to "mayday")).statusCode())
        assertEquals(200, mcp(rpc(1, "ping"), cookie + ("X-Requested-With" to "mayday")).statusCode())
    }

    // ---- alert intake ----

    @Test
    fun `alert intake answers 201 for new alerts and 200 for duplicates`() {
        val h = bearerHeader(scoped("member", TokenScope.ALERTS_INGEST))
        val body = """{"title":"Queue lag","severity":"SEV4","team":"checkout","fingerprint":"lag-${unique()}","labels":{"q":"orders"}}"""
        val first = send("POST", "/api/v1/alerts", body, h)
        assertEquals(201, first.statusCode(), first.body())
        val created = json(first).jsonObject
        assertEquals(true.toString(), created["created"]!!.jsonPrimitive.content)
        assertEquals("api", created["alert"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        val second = send("POST", "/api/v1/alerts", body, h)
        assertEquals(200, second.statusCode())
        assertEquals(2, json(second).jsonObject["alert"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertError(send("POST", "/api/v1/alerts", """{"severity":"SEV4"}""", h), 400, "invalid")
        assertError(send("POST", "/api/v1/alerts", "", h), 400, "invalid")
    }

    @Test
    fun `alertmanager webhooks map labels, severity and status`() = suspendTest {
        val h = bearerHeader(scoped("admin", TokenScope.ALERTS_INGEST))
        val fp = "am-${unique()}"
        val firing = """{"alerts":[{"status":"firing","fingerprint":"$fp","generatorURL":"http://prom/graph","labels":{"alertname":"HighErrors","severity":"critical","team":"checkout"},"annotations":{"summary":"5xx above 5%","description":"Checkout errors"}},
            {"status":"firing","fingerprint":"$fp-2","labels":{"alertname":"Slow","severity":"warning"}}]}"""
        val r = send("POST", "/api/v1/integrations/alertmanager?team=sre", firing, h)
        assertEquals(200, r.statusCode(), r.body())
        assertEquals(mapOf("created" to 2, "updated" to 0), json(r).jsonObject.mapValues { it.value.jsonPrimitive.int })
        val alerts = app.alerts.list(principal("viewer"), AlertFilter(), null, 200).items
        val high = alerts.single { it.fingerprint == fp }
        assertEquals(Severity.SEV1, high.severity)
        assertEquals("5xx above 5%", high.title)
        assertEquals("Checkout errors", high.description)
        assertEquals("alertmanager", high.source)
        assertEquals("http://prom/graph", high.externalUrl)
        assertEquals(other.id, high.teamId, "?team= wins over the team label")
        val slow = alerts.single { it.fingerprint == "$fp-2" }
        assertEquals(Severity.SEV3, slow.severity)
        assertEquals("Slow", slow.title)

        val resolved = send("POST", "/api/v1/integrations/alertmanager", firing.replace("\"firing\"", "\"resolved\"").substringBefore(",\n") + "]}", h)
        assertEquals(mapOf("created" to 0, "updated" to 1), json(resolved).jsonObject.mapValues { it.value.jsonPrimitive.int })
        assertEquals(AlertStatus.RESOLVED, app.alerts.get(principal("viewer"), high.id).status)

        assertError(send("POST", "/api/v1/integrations/alertmanager", """{"alerts":[{"labels":{"alertname":"NoTeam"}}]}""", h), 400, "invalid")
        assertError(send("POST", "/api/v1/integrations/alertmanager", "[]", h), 400, "invalid")
    }

    // ---- static files and operations ----

    @Test
    fun `unknown GET paths fall back to the web app with security headers`() {
        val r = send("GET", "/incidents/INC-1")
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("text/html"))
        assertTrue(r.body().contains("<title>") && (r.body() == WebFilters.PLACEHOLDER || r.body().contains("<div id=")), r.body().take(200))
        assertEquals(r.body(), send("GET", "/").body())
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").get())
        assertEquals("DENY", r.headers().firstValue("X-Frame-Options").get())
        assertEquals("same-origin", r.headers().firstValue("Referrer-Policy").get())
        assertTrue(r.headers().firstValue("Content-Security-Policy").get().contains("frame-ancestors 'none'"))

        assertEquals(404, send("GET", "/no-such-file.js").statusCode())
        val api = send("GET", "/api/v1/nothing")
        assertEquals(404, api.statusCode())
        assertNull(api.headers().firstValue("Content-Security-Policy").orElse(null))
        assertEquals("nosniff", api.headers().firstValue("X-Content-Type-Options").get())
    }

    @Test
    fun `health, readiness and metrics respond`() {
        val health = send("GET", "/healthz")
        assertEquals(200, health.statusCode())
        assertEquals("UP", json(health).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(200, send("GET", "/livez").statusCode())
        assertEquals(200, send("GET", "/readyz").statusCode())
        val metrics = send("GET", "/metrics")
        assertEquals(200, metrics.statusCode())
        assertTrue(metrics.body().contains("mayday_live_connections"), metrics.body().take(300))
        assertNotNull(metrics.body().lines().firstOrNull { it.startsWith("jvm_") })
    }
}
