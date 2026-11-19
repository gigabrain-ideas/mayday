package mayday.agents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import mayday.alerting.Alert
import mayday.alerting.AlertAutomation
import mayday.alerting.AlertService
import mayday.identity.Access
import mayday.identity.AgentIdentity
import mayday.identity.AgentIdentityLookup
import mayday.identity.IdentityService
import mayday.identity.IssuedToken
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.identity.TokenScope
import mayday.incidents.EntryKind
import mayday.incidents.IncidentService
import mayday.policy.Keys
import mayday.policy.PolicyReader
import mayday.shared.Actor
import mayday.shared.ActorKind
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.DomainEvent
import mayday.shared.EventPublisher
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.JSON
import mayday.shared.NotFound
import mayday.shared.SecretBox
import mayday.shared.Unavailable
import java.net.URI
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

const val INTERNAL_AGENT_ID = "mayday"

@Serializable
data class AgentInput(
    val name: String,
    val description: String = "",
    val kind: AgentKind = AgentKind.EXTERNAL,
    val delivery: Delivery = Delivery.PUSH,
    val endpointUrl: String? = null,
    val actions: Set<AgentAction> = setOf(AgentAction.ANALYZE),
    val triggers: List<AgentTrigger> = emptyList(),
    val role: OrgRole = OrgRole.VIEWER,
    val enabled: Boolean = true,
    val instructions: String = "",
)

@Serializable
data class AgentCredentials(val agent: AgentConfig, val token: IssuedToken? = null, val signingSecret: String? = null)

@Serializable
data class ClaimedRun(val run: AgentRun, val delegatedToken: String? = null, val subject: JsonObject)

@Serializable
data class LlmInput(
    val model: String? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val effort: String? = null,
    val enabled: Boolean? = null,
)

@Serializable
data class LlmView(val provider: String, val model: String, val baseUrl: String, val effort: String, val enabled: Boolean, val hasApiKey: Boolean, val ready: Boolean)

@Serializable
data class McpServerInput(
    val name: String,
    val url: String,
    val headerName: String = "Authorization",
    val credential: String? = null,
    val toolAllowlist: List<String> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class McpServerView(val id: String, val name: String, val url: String, val headerName: String, val hasCredential: Boolean, val toolAllowlist: List<String>, val enabled: Boolean)

class AgentService(
    private val agents: AgentRepository,
    private val runs: RunRepository,
    private val mcpServers: McpServerRepository,
    private val config: ConfigRepository,
    private val incidents: IncidentService,
    private val alerts: AlertService,
    private val identity: () -> IdentityService,
    private val policy: PolicyReader,
    private val box: SecretBox,
    private val llmFactory: LlmProviderFactory,
    private val dispatcher: AgentDispatcher,
    private val tools: () -> ToolCatalog,
    private val events: EventPublisher,
    private val clock: Clock,
    private val publicUrl: String,
    private val envApiKey: String?,
    concurrency: Int = 16,
) : AgentIdentityLookup, AlertAutomation {
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val permits = Semaphore(concurrency)
    private val delegationTtl = Duration.ofHours(1)

    override fun find(agentId: String): AgentIdentity? =
        agents.get(agentId)?.let { AgentIdentity(it.id, it.name, it.role, it.enabled) }

    fun ensureInternalAgent() {
        if (agents.get(INTERNAL_AGENT_ID) != null) return
        val now = clock.now()
        agents.save(
            AgentConfig(
                id = INTERNAL_AGENT_ID,
                name = "Mayday agent",
                description = "Built-in agent. It uses the configured LLM provider and the tool gateway.",
                kind = AgentKind.INTERNAL,
                actions = AgentAction.entries.toSet(),
                triggers = listOf(AgentTrigger(minSeverity = mayday.shared.Severity.SEV2, action = AgentAction.ANALYZE)),
                role = OrgRole.MEMBER,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    // ---- configuration (admin) ----

    fun list(by: Principal): List<AgentConfig> = Access.requireRead(by).let { agents.list().sortedBy { it.name.lowercase() }.map(::redact) }
    fun get(by: Principal, id: String) = Access.requireRead(by).let { redact(agents.get(id) ?: throw NotFound("Agent")) }

    private fun redact(a: AgentConfig) = a.copy(secretSealed = if (a.secretSealed.isEmpty()) "" else "set")

    fun create(by: Principal, input: AgentInput): AgentCredentials {
        Access.requireAdmin(by)
        if (input.kind == AgentKind.INTERNAL) throw Invalid("The built-in agent exists already. Create external agents only.")
        validate(input)
        val now = clock.now()
        val secret = Ids.secret(32)
        val agent = AgentConfig(
            id = Ids.new(),
            name = input.name.trim(),
            description = input.description,
            kind = AgentKind.EXTERNAL,
            delivery = input.delivery,
            endpointUrl = input.endpointUrl?.trim()?.ifEmpty { null },
            secretSealed = box.seal(secret),
            actions = input.actions,
            triggers = input.triggers,
            role = input.role,
            enabled = input.enabled,
            instructions = input.instructions,
            createdAt = now,
            updatedAt = now,
        )
        agents.save(agent)
        val token = identity().issueAgentToken(agent.id, "${agent.name} identity", scopesFor(agent.role))
        events.publish(DomainEvent("agent.changed", agent.id, now))
        return AgentCredentials(redact(agent), token, secret)
    }

    fun update(by: Principal, id: String, input: AgentInput): AgentConfig {
        Access.requireAdmin(by)
        val cur = agents.get(id) ?: throw NotFound("Agent")
        if (cur.kind == AgentKind.EXTERNAL) validate(input)
        val next = cur.copy(
            name = input.name.trim().ifEmpty { cur.name },
            description = input.description,
            delivery = if (cur.kind == AgentKind.EXTERNAL) input.delivery else cur.delivery,
            endpointUrl = if (cur.kind == AgentKind.EXTERNAL) input.endpointUrl?.trim()?.ifEmpty { null } else null,
            actions = input.actions,
            triggers = input.triggers,
            role = input.role,
            enabled = input.enabled,
            instructions = input.instructions,
            updatedAt = clock.now(),
        )
        agents.save(next)
        if (cur.role != next.role && next.kind == AgentKind.EXTERNAL) {
            identity().revokeAgentTokens(id)
        }
        events.publish(DomainEvent("agent.changed", id, clock.now()))
        return redact(next)
    }

    fun rotateCredentials(by: Principal, id: String): AgentCredentials {
        Access.requireAdmin(by)
        val cur = agents.get(id) ?: throw NotFound("Agent")
        if (cur.kind != AgentKind.EXTERNAL) throw Invalid("The built-in agent has no credentials")
        identity().revokeAgentTokens(id)
        val secret = Ids.secret(32)
        val next = cur.copy(secretSealed = box.seal(secret), updatedAt = clock.now())
        agents.save(next)
        val token = identity().issueAgentToken(id, "${cur.name} identity", scopesFor(cur.role))
        return AgentCredentials(redact(next), token, secret)
    }

    fun delete(by: Principal, id: String) {
        Access.requireAdmin(by)
        if (id == INTERNAL_AGENT_ID) throw Invalid("Disable the built-in agent instead of deleting it")
        agents.get(id) ?: throw NotFound("Agent")
        identity().revokeAgentTokens(id)
        agents.delete(id)
        events.publish(DomainEvent("agent.changed", id, clock.now()))
    }

    private fun scopesFor(role: OrgRole) = when (role) {
        OrgRole.VIEWER -> setOf(TokenScope.READ)
        OrgRole.MEMBER -> setOf(TokenScope.READ, TokenScope.WRITE)
        OrgRole.ADMIN -> Principal.ALL
    }

    private fun validate(input: AgentInput) {
        if (input.name.isBlank()) throw Invalid("Name is required")
        if (input.actions.isEmpty()) throw Invalid("Select at least one action")
        if (input.delivery == Delivery.PUSH) {
            val url = input.endpointUrl?.trim().orEmpty()
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw Invalid("A push agent needs an http(s) endpoint URL")
            }
        }
        input.triggers.forEach { if (it.action !in input.actions) throw Invalid("Trigger action ${it.action} is not enabled for this agent") }
    }

    // ---- LLM provider and tool gateway (admin) ----

    fun llm(by: Principal): LlmView = Access.requireRead(by).let { view(config.llm() ?: LlmConfig()) }

    private fun view(c: LlmConfig) = LlmView(c.provider, c.model, c.baseUrl, c.effort, c.enabled,
        c.apiKeySealed.isNotEmpty() || !envApiKey.isNullOrBlank(), llmReady(c))

    fun updateLlm(by: Principal, input: LlmInput): LlmView {
        Access.requireAdmin(by)
        val cur = config.llm() ?: LlmConfig()
        val effort = input.effort ?: cur.effort
        if (effort !in setOf("low", "medium", "high", "xhigh", "max")) throw Invalid("Effort must be low, medium, high, xhigh or max")
        val next = cur.copy(
            model = input.model?.trim()?.ifEmpty { null } ?: cur.model,
            apiKeySealed = when {
                input.apiKey == null -> cur.apiKeySealed
                input.apiKey.isBlank() -> ""
                else -> box.seal(input.apiKey.trim())
            },
            baseUrl = input.baseUrl?.trim() ?: cur.baseUrl,
            effort = effort,
            enabled = input.enabled ?: cur.enabled,
        )
        config.saveLlm(next)
        return view(next)
    }

    private fun llmReady(c: LlmConfig) = c.enabled && (c.apiKeySealed.isNotEmpty() || !envApiKey.isNullOrBlank())

    fun internalReady(): Boolean = llmReady(config.llm() ?: LlmConfig()) && agents.get(INTERNAL_AGENT_ID)?.enabled == true

    private fun llmProvider(): LlmProvider {
        val c = config.llm() ?: throw Unavailable("No LLM provider is configured")
        if (!llmReady(c)) throw Unavailable("The LLM provider is disabled or has no API key")
        val key = if (c.apiKeySealed.isNotEmpty()) box.open(c.apiKeySealed) else envApiKey.orEmpty()
        return llmFactory.create(c, key)
    }

    fun mcpServers(by: Principal): List<McpServerView> {
        Access.requireRead(by)
        return mcpServers.list().sortedBy { it.name.lowercase() }.map { McpServerView(it.id, it.name, it.url, it.headerName, it.credentialSealed.isNotEmpty(), it.toolAllowlist, it.enabled) }
    }

    fun saveMcpServer(by: Principal, id: String?, input: McpServerInput): McpServerView {
        Access.requireAdmin(by)
        val uri = runCatching { URI(input.url.trim()) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https")) throw Invalid("The MCP server URL must use http or https")
        if (input.name.isBlank()) throw Invalid("Name is required")
        val cur = id?.let { mcpServers.get(it) ?: throw NotFound("MCP server") }
        val s = McpServerConfig(
            id = cur?.id ?: Ids.new(),
            name = input.name.trim(),
            url = input.url.trim(),
            headerName = input.headerName.ifBlank { "Authorization" },
            credentialSealed = when {
                input.credential == null -> cur?.credentialSealed.orEmpty()
                input.credential.isBlank() -> ""
                else -> box.seal(input.credential)
            },
            toolAllowlist = input.toolAllowlist.map { it.trim() }.filter { it.isNotEmpty() },
            enabled = input.enabled,
            createdAt = cur?.createdAt ?: clock.now(),
        )
        mcpServers.save(s)
        events.publish(DomainEvent("mcp_server.changed", s.id, clock.now()))
        return McpServerView(s.id, s.name, s.url, s.headerName, s.credentialSealed.isNotEmpty(), s.toolAllowlist, s.enabled)
    }

    fun deleteMcpServer(by: Principal, id: String) {
        Access.requireAdmin(by)
        mcpServers.delete(id)
        events.publish(DomainEvent("mcp_server.changed", id, clock.now()))
    }

    // ---- runs ----

    fun runsFor(by: Principal, subjectId: String): List<AgentRun> = Access.requireRead(by).let { runs.bySubject(subjectId).sortedByDescending { it.createdAt } }
    fun recentRuns(by: Principal, limit: Int) = Access.requireRead(by).let { runs.recent(limit.coerceIn(1, 200)) }
    fun run(by: Principal, id: String): AgentRun = Access.requireRead(by).let { runs.get(id) ?: throw NotFound("Agent run") }

    /** Agents that can perform [action] right now, the built-in agent first. */
    fun eligible(action: AgentAction): List<AgentConfig> = agents.list()
        .filter { it.enabled && action in it.actions && (it.kind == AgentKind.EXTERNAL || internalReady()) }
        .sortedBy { if (it.kind == AgentKind.INTERNAL) 0 else 1 }
        .map(::redact)

    /**
     * A human presses a button. The agent receives a delegated token and acts on behalf of that human,
     * so the human's RBAC applies to every change the agent makes.
     */
    fun trigger(by: Principal, action: AgentAction, subjectKind: SubjectKind, subjectRef: String, agentId: String?, prompt: String): AgentRun {
        Access.requireWrite(by)
        val user = by.user ?: throw Forbidden("Only a person can start an agent manually")
        if (by.isDelegated) throw Forbidden("An agent cannot start another agent")
        val agent = if (agentId.isNullOrBlank()) {
            eligible(action).firstOrNull()?.let { agents.get(it.id) }
                ?: throw Unavailable("No agent can ${action.label.lowercase()}. Configure the LLM provider or an external agent.")
        } else {
            agents.get(agentId) ?: throw NotFound("Agent")
        }
        if (!agent.enabled) throw Conflict("${agent.name} is disabled")
        if (action !in agent.actions) throw Invalid("${agent.name} does not support '${action.label}'")
        if (agent.kind == AgentKind.INTERNAL && !internalReady()) throw Unavailable("Configure the LLM provider to use the built-in agent")
        val (subjectId, label) = subject(by, subjectKind, subjectRef)
        val run = AgentRun(
            id = Ids.new(),
            agentId = agent.id,
            agentName = agent.name,
            action = action,
            subjectKind = subjectKind,
            subjectId = subjectId,
            subjectLabel = label,
            trigger = RunTrigger.MANUAL,
            requestedBy = by.actor,
            onBehalfOf = user.id,
            prompt = prompt.take(8000),
            createdAt = clock.now(),
        )
        runs.save(run, null)
        note(run, "${by.actor.name} started ${agent.name}: ${action.label}.")
        start(agent, run)
        return runs.get(run.id) ?: run
    }

    override fun onAlertCreated(alert: Alert) {
        val teams = listOf(alert.teamId)
        for (agent in agents.list()) {
            if (!agent.enabled) continue
            if (agent.kind == AgentKind.INTERNAL && !internalReady()) continue
            for (t in agent.triggers) {
                if (t.event != TriggerEvent.ALERT_CREATED) continue
                if (!alert.severity.atLeast(t.minSeverity)) continue
                if (t.teamIds.isNotEmpty() && alert.teamId !in t.teamIds) continue
                val allowed = if (t.action.writes) policy.bool(Keys.AGENT_AUTO_REMEDIATION, teams) else policy.bool(Keys.AGENT_AUTO_ANALYSIS, teams)
                if (!allowed) continue
                val (kind, id, label) = if (alert.incidentId != null) {
                    val inc = incidents.get(Principal.system, alert.incidentId)
                    Triple(SubjectKind.INCIDENT, inc.id, inc.key)
                } else Triple(SubjectKind.ALERT, alert.id, alert.title.take(80))
                val run = AgentRun(
                    id = Ids.new(),
                    agentId = agent.id,
                    agentName = agent.name,
                    action = t.action,
                    subjectKind = kind,
                    subjectId = id,
                    subjectLabel = label,
                    trigger = RunTrigger.AUTO,
                    requestedBy = Actor.system,
                    createdAt = clock.now(),
                )
                runs.save(run, null)
                note(run, "${agent.name} started automatically: ${t.action.label}.")
                start(agent, run)
            }
        }
    }

    private fun start(agent: AgentConfig, run: AgentRun) {
        publish(run)
        when {
            agent.kind == AgentKind.INTERNAL -> executor.submit { permits.acquire(); try { executeInternal(agent, run.id) } finally { permits.release() } }
            agent.delivery == Delivery.PUSH -> executor.submit { push(agent, run.id) }
            else -> Unit
        }
    }

    private fun push(agent: AgentConfig, runId: String) {
        val run = runs.get(runId) ?: return
        try {
            val payload = DispatchPayload(
                event = "agent_run.requested",
                run = run,
                maydayUrl = publicUrl,
                mcpUrl = "$publicUrl/mcp",
                delegatedToken = delegatedToken(agent, run),
                subject = snapshot(run),
            )
            dispatcher.dispatch(agent, box.open(agent.secretSealed), payload)
            transition(runId) { it.copy(status = RunStatus.DISPATCHED, startedAt = clock.now()) }
        } catch (e: Exception) {
            finish(runId, RunStatus.FAILED, "", "Delivery failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun claim(by: Principal, limit: Int): List<ClaimedRun> {
        if (!by.isAgent || by.isDelegated) throw Forbidden("Only an agent identity can claim runs")
        val agent = agents.get(by.actor.id) ?: throw NotFound("Agent")
        return runs.claim(agent.id, limit.coerceIn(1, 50), clock.now()).map {
            publish(it)
            ClaimedRun(it, delegatedToken(agent, it), snapshot(it))
        }
    }

    fun complete(by: Principal, runId: String, success: Boolean, output: String, error: String): AgentRun {
        if (!by.isAgent) throw Forbidden("Only the agent that owns the run can complete it")
        val run = runs.get(runId) ?: throw NotFound("Agent run")
        if (run.agentId != by.actor.id) throw Forbidden("This run belongs to another agent")
        if (run.status.terminal) throw Conflict("The run is already finished")
        return finish(runId, if (success) RunStatus.SUCCEEDED else RunStatus.FAILED, output, error)
    }

    fun progress(by: Principal, runId: String, text: String): AgentRun {
        val run = runs.get(runId) ?: throw NotFound("Agent run")
        if (run.agentId != by.actor.id) throw Forbidden("This run belongs to another agent")
        if (run.status.terminal) throw Conflict("The run is already finished")
        val next = transition(runId) { it.copy(status = RunStatus.RUNNING, startedAt = it.startedAt ?: clock.now()) }
        if (text.isNotBlank()) note(run, text.trim(), Actor(ActorKind.AGENT, run.agentId, run.agentName, run.onBehalfOf))
        return next
    }

    fun cancel(by: Principal, runId: String): AgentRun {
        Access.requireWrite(by)
        val run = runs.get(runId) ?: throw NotFound("Agent run")
        if (run.status.terminal) return run
        return finish(runId, RunStatus.CANCELLED, run.output, "Cancelled by ${by.actor.name}")
    }

    /** Mark runs that stopped reporting as failed so the queue does not grow without bound. */
    fun reapStale(maxAge: Duration) {
        val limit = clock.now().minus(maxAge)
        runs.active().filter { (it.startedAt ?: it.createdAt).isBefore(limit) }.forEach {
            finish(it.id, RunStatus.FAILED, it.output, "The agent did not report a result in time")
        }
    }

    private fun executeInternal(agent: AgentConfig, runId: String) {
        val run = transition(runId) { it.copy(status = RunStatus.RUNNING, startedAt = clock.now()) }
        try {
            val principal = principalFor(agent, run)
            val result = InternalAgent(llmProvider(), tools(), clock).run(agent, run, principal, snapshot(run))
            finish(runId, RunStatus.SUCCEEDED, result, "")
        } catch (e: Exception) {
            finish(runId, RunStatus.FAILED, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun principalFor(agent: AgentConfig, run: AgentRun): Principal {
        val user = run.onBehalfOf?.let { runCatching { identity().user(it) }.getOrNull() }
        val actor = Actor(ActorKind.AGENT, agent.id, agent.name, onBehalfOf = user?.id)
        val scopes = if (user != null) Principal.ALL else scopesFor(agent.role)
        return Principal(actor, user, agent.role, scopes, run.id)
    }

    private fun delegatedToken(agent: AgentConfig, run: AgentRun): String? {
        val userId = run.onBehalfOf ?: return null
        val user = runCatching { identity().user(userId) }.getOrNull() ?: return null
        return identity().delegate(user, AgentIdentity(agent.id, agent.name, agent.role, agent.enabled), run.id, delegationTtl, Principal.ALL)
    }

    private fun finish(runId: String, status: RunStatus, output: String, error: String): AgentRun {
        val done = transition(runId) { it.copy(status = status, output = output.take(100_000), error = error.take(4000), finishedAt = clock.now()) }
        val agentActor = Actor(ActorKind.AGENT, done.agentId, done.agentName, done.onBehalfOf)
        when (status) {
            RunStatus.SUCCEEDED -> if (output.isNotBlank()) note(done, output.take(20_000), agentActor)
            RunStatus.FAILED -> note(done, "${done.agentName} failed: ${error.take(500)}", agentActor)
            else -> Unit
        }
        return done
    }

    private fun transition(runId: String, f: (AgentRun) -> AgentRun): AgentRun {
        repeat(5) {
            val cur = runs.get(runId) ?: throw NotFound("Agent run")
            val next = f(cur).copy(version = cur.version + 1)
            try {
                runs.save(next, cur.version)
                publish(next)
                return next
            } catch (_: Conflict) {
            }
        }
        throw Conflict("The run changed too often; try again")
    }

    private fun note(run: AgentRun, text: String, actor: Actor = Actor.system) {
        when (run.subjectKind) {
            SubjectKind.INCIDENT -> incidents.systemEntry(run.subjectId, EntryKind.AGENT, actor, text, mapOf("runId" to run.id))
            SubjectKind.ALERT -> runCatching { alerts.addUpdate(Principal.system, run.subjectId, text) }
        }
    }

    private fun subject(by: Principal, kind: SubjectKind, ref: String): Pair<String, String> = when (kind) {
        SubjectKind.INCIDENT -> incidents.get(by, ref).let { it.id to it.key }
        SubjectKind.ALERT -> alerts.get(by, ref).let { it.id to it.title.take(80) }
    }

    private fun snapshot(run: AgentRun): JsonObject = when (run.subjectKind) {
        SubjectKind.INCIDENT -> {
            val inc = incidents.get(Principal.system, run.subjectId)
            buildJsonObject {
                put("kind", "incident")
                put("incident", JSON.encodeToJsonElement(inc))
                put("timeline", JSON.encodeToJsonElement(incidents.timeline(Principal.system, inc.id).takeLast(200)))
            }
        }
        SubjectKind.ALERT -> buildJsonObject {
            put("kind", "alert")
            put("alert", JSON.encodeToJsonElement(alerts.get(Principal.system, run.subjectId)))
        }
    }

    private fun publish(run: AgentRun) =
        events.publish(DomainEvent("agent_run.updated", run.id, clock.now(), data = mapOf("subjectId" to run.subjectId, "status" to run.status.name)))

    fun shutdown() = executor.shutdownNow()
}
