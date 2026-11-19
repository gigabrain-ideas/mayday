package mayday

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

/** Scripted LLM: returns the queued responses in order and records the requests. */
class FakeLlm : LlmProvider, LlmProviderFactory {
    val requests = CopyOnWriteArrayList<LlmRequest>()
    val script = ArrayDeque<(LlmRequest) -> LlmResponse>()

    override fun create(config: mayday.agents.LlmConfig, apiKey: String) = this

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        val next = synchronized(script) { script.removeFirstOrNull() } ?: return text("done")
        return next(request)
    }

    fun toolCall(name: String, input: kotlinx.serialization.json.JsonObject) = script.addLast {
        LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.ToolUse(Ids.new(), name, input))), StopReason.TOOL_USE)
    }

    fun answer(t: String) = script.addLast { text(t) }

    private fun text(t: String) = LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.Text(t))), StopReason.END)
}

class RecordingDispatcher : AgentDispatcher {
    val sent = CopyOnWriteArrayList<Pair<AgentConfig, DispatchPayload>>()
    var fail = false
    override fun dispatch(agent: AgentConfig, secret: String, payload: DispatchPayload) {
        if (fail) throw IllegalStateException("endpoint down")
        sent += agent to payload
    }
}

class Fixture {
    val llm = FakeLlm()
    val dispatcher = RecordingDispatcher()
    val events = InProcessEventBus()
    val store = MemoryStore(null)
    val app = App(
        Config(secretKey = "test-secret", publicUrl = "http://mayday.test", dataDir = null),
        Ports.memory(store, events).let {
            Ports(it.projects, it.teams, it.users, it.tokens, it.settings, it.changeRequests, it.incidents, it.timeline, it.alerts,
                it.agents, it.runs, it.mcpServers, it.config, events, llm, dispatcher)
        },
    )
    val project = app.directory.createProject("Payments", "payments")
    val team = app.directory.createTeam("Checkout", "checkout", project.id)
    val other = app.directory.createTeam("SRE", "sre", null)

    private fun user(email: String, role: OrgRole, teams: Map<String, TeamRole> = emptyMap(), projectAdmin: Set<String> = emptySet()) =
        User(Ids.new(), email, email.substringBefore('@'), role, teams, projectAdmin, createdAt = Instant.now()).also { store.users.save(it) }

    val adminUser = user("admin@example.com", OrgRole.ADMIN)
    val teamAdminUser = user("lead@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.ADMIN))
    val memberUser = user("member@example.com", OrgRole.MEMBER, mapOf(team.id to TeamRole.MEMBER))
    val viewerUser = user("viewer@example.com", OrgRole.VIEWER)

    val admin: Principal get() = app.identity.principalForUser(adminUser)
    val lead: Principal get() = app.identity.principalForUser(teamAdminUser)
    val member: Principal get() = app.identity.principalForUser(memberUser)
    val viewer: Principal get() = app.identity.principalForUser(viewerUser)

    fun enableLlm() = app.agents.updateLlm(admin, mayday.agents.LlmInput(apiKey = "sk-test", enabled = true))

    fun awaitRun(id: String, timeoutMs: Long = 5000): mayday.agents.AgentRun {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = store.runs.get(id)!!
            if (r.status.terminal) return r
            Thread.sleep(10)
        }
        error("run $id did not finish")
    }
}
