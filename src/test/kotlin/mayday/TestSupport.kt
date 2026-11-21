package mayday

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mayday.adapters.memory.MemoryStore
import mayday.agents.AgentConfig
import mayday.agents.AgentDispatcher
import mayday.agents.DispatchPayload
import mayday.agents.LlmBlock
import mayday.agents.LlmMessage
import mayday.agents.LlmProvider
import mayday.agents.LlmProviderFactory
import mayday.agents.LlmRequest
import mayday.agents.LlmResponse
import mayday.agents.LlmRole
import mayday.agents.StopReason
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.identity.TeamRole
import mayday.identity.User
import mayday.shared.Ids
import mayday.shared.InProcessEventBus
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Runs a suspending test body on a real dispatcher, so background agent work and real-time waits behave as in production. */
fun suspendTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking(block = block)
}

/** Polls [condition] until it is true or the timeout ends. */
suspend fun eventually(timeoutMs: Long = 5000, message: String = "condition not met", condition: suspend () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return
        delay(10)
    }
    error(message)
}

/** Scripted LLM: returns the queued responses in order and records the requests. */
class FakeLlm : LlmProvider, LlmProviderFactory {
    val requests = CopyOnWriteArrayList<LlmRequest>()
    val script = ArrayDeque<(LlmRequest) -> LlmResponse>()
    val auths = CopyOnWriteArrayList<mayday.agents.GatewayAuth>()

    /** When set, every call waits for it, like a slow or hanging gateway. */
    @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun create(config: mayday.agents.LlmConfig, auth: mayday.agents.GatewayAuth) = this.also { auths += auth }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        gate?.await()
        val next = synchronized(script) { script.removeFirstOrNull() } ?: return text("done")
        return next(request)
    }

    fun toolCall(name: String, input: kotlinx.serialization.json.JsonObject) = synchronized(script) {
        script.addLast { LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.ToolUse(Ids.new(), name, input))), StopReason.TOOL_USE) }
    }

    fun answer(t: String) = synchronized(script) { script.addLast { text(t) } }

    fun fail(e: Exception) = synchronized(script) { script.addLast { throw e } }

    private fun text(t: String) = LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.Text(t))), StopReason.END)
}

class RecordingDispatcher : AgentDispatcher {
    val sent = CopyOnWriteArrayList<Pair<AgentConfig, DispatchPayload>>()
    @Volatile var fail = false

    override suspend fun dispatch(agent: AgentConfig, secret: String, payload: DispatchPayload) {
        if (fail) throw IllegalStateException("endpoint down")
        sent += agent to payload
    }
}

class Fixture(llmEnv: mayday.agents.LlmEnvironment = mayday.agents.LlmEnvironment(), extraTools: List<mayday.agents.ToolCatalog> = emptyList()) {
    val llm = FakeLlm()
    val dispatcher = RecordingDispatcher()
    val events = InProcessEventBus()
    val store = MemoryStore(null)
    val app = App(
        AppSettings(secretKey = "test-secret", publicUrl = "http://mayday.test", llm = llmEnv),
        Ports.memory(store, events).with(llm = llm, dispatcher = dispatcher),
    ).also { a -> if (extraTools.isNotEmpty()) a.tools = mayday.tools.CompositeCatalog(listOf(a.tools) + extraTools) }
    val project = runBlocking { app.start(); app.directory.createProject("Payments", "payments") }
    val team = runBlocking { app.directory.createTeam("Checkout", "checkout", project.id) }
    val other = runBlocking { app.directory.createTeam("SRE", "sre", null) }

    private fun user(email: String, role: OrgRole, teams: Map<String, TeamRole> = emptyMap(), projectAdmin: Set<String> = emptySet()) =
        User(Ids.new(), email, email.substringBefore('@'), role, teams, projectAdmin, createdAt = Instant.now()).also { runBlocking { store.users.save(it) } }

    val adminUser = user("admin@example.com", OrgRole.ADMIN)
    val teamAdminUser = user("lead@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.ADMIN))
    val memberUser = user("member@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.MEMBER))
    val viewerUser = user("viewer@example.com", OrgRole.VIEWER)

    val admin: Principal get() = app.identity.principalForUser(adminUser)
    val lead: Principal get() = app.identity.principalForUser(teamAdminUser)
    val member: Principal get() = app.identity.principalForUser(memberUser)
    val viewer: Principal get() = app.identity.principalForUser(viewerUser)

    suspend fun enableLlm() = app.agents.updateLlm(admin, mayday.agents.LlmInput(apiKey = "sk-test", enabled = true))

    suspend fun awaitRun(id: String, timeoutMs: Long = 5000): mayday.agents.AgentRun {
        eventually(timeoutMs, "run $id did not finish") { store.runs.get(id)!!.status.terminal }
        return store.runs.get(id)!!
    }
}
