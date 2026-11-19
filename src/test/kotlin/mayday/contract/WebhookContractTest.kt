package mayday.contract

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.adapters.dispatch.WebhookDispatcher
import mayday.agents.AgentAction
import mayday.agents.AgentConfig
import mayday.agents.AgentKind
import mayday.agents.AgentRun
import mayday.agents.DispatchPayload
import mayday.agents.RunTrigger
import mayday.agents.SubjectKind
import mayday.shared.Actor
import mayday.shared.Crypto
import mayday.shared.JSON
import mayday.suspendTest
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Contract for push agents: what a receiver gets and how it verifies it. */
class WebhookContractTest {
    private val now = Instant.now()
    private val run = AgentRun("r1", "a1", "Agent", AgentAction.ANALYZE, SubjectKind.INCIDENT, "i1", "INC-1", trigger = RunTrigger.MANUAL, requestedBy = Actor.system, createdAt = now)
    private fun agent(url: String) = AgentConfig("a1", "Agent", kind = AgentKind.EXTERNAL, endpointUrl = url, actions = setOf(AgentAction.ANALYZE), createdAt = now, updatedAt = now)
    private val payload = DispatchPayload("agent_run.requested", run, "http://m", "http://m/mcp", "tok", JsonObject(emptyMap()))

    private fun hook(status: Int, received: CompletableFuture<Triple<String, String, String>> = CompletableFuture()) =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/hook") { ex ->
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                received.complete(Triple(body, ex.requestHeaders.getFirst("X-Mayday-Timestamp"), ex.requestHeaders.getFirst("X-Mayday-Signature")))
                ex.sendResponseHeaders(status, -1); ex.close()
            }
            start()
        }

    @Test
    fun `webhook body is signed with the agent secret and timestamp`() = suspendTest {
        val received = CompletableFuture<Triple<String, String, String>>()
        val server = hook(204, received)
        try {
            WebhookDispatcher().dispatch(agent("http://127.0.0.1:${server.address.port}/hook"), "s3cret", payload)
            val (body, ts, sig) = received.get(5, TimeUnit.SECONDS)
            assertEquals("sha256=" + Crypto.hmacHex("s3cret", "$ts.$body"), sig)
            val parsed = JSON.parseToJsonElement(body).jsonObject
            assertEquals("tok", parsed["delegatedToken"]!!.jsonPrimitive.content)
            assertEquals("r1", parsed["run"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a non-2xx answer is a failed delivery`() = suspendTest {
        val server = hook(400)
        try {
            assertFails { WebhookDispatcher().dispatch(agent("http://127.0.0.1:${server.address.port}/hook"), "s3cret", payload) }
        } finally {
            server.stop(0)
        }
    }
}
