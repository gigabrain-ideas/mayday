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
import mayday.shared.SpanKind
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import mayday.shared.Unavailable
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.time.Duration
import java.time.Instant

const val INTERNAL_AGENT_ID = "mayday"

/** Span attribute that the telemetry adapter turns into a link to the given W3C traceparent. */
private const val LINK = "mayday.link.traceparent"

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

/** Null keeps the stored value. An empty secret removes it. */
@Serializable
data class LlmInput(
    val provider: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val effort: String? = null,
    val enabled: Boolean? = null,
    val authStyle: AuthStyle? = null,
    val credential: CredentialMode? = null,
    val tokenUrl: String? = null,
    val issuer: String? = null,
    val grant: OAuthGrant? = null,
    val clientAuth: ClientAuth? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val assertionFile: String? = null,
    val tokenFile: String? = null,
    val scope: String? = null,
    val tokenParams: Map<String, String>? = null,
    val headers: Map<String, String>? = null,
)

@Serializable
data class LlmView(
    val provider: String,
    val model: String,
    val baseUrl: String,
    val effort: String,
    val enabled: Boolean,
    val hasApiKey: Boolean,
    val ready: Boolean,
    val authStyle: AuthStyle = AuthStyle.API_KEY,
    val credential: CredentialMode = CredentialMode.STATIC,
    val tokenUrl: String = "",
    val issuer: String = "",
    val grant: OAuthGrant = OAuthGrant.CLIENT_CREDENTIALS,
    val clientAuth: ClientAuth = ClientAuth.BASIC,
    val clientId: String = "",
    val hasClientSecret: Boolean = false,
    val assertionFile: String = "",
    val tokenFile: String = "",
    val scope: String = "",
    val tokenParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    /** Fields that the environment sets. The UI shows them read-only. */
    val managedFields: List<String> = emptyList(),
    /** Set after the gateway answered HTTP 429. Mayday makes no LLM call before this time. */
    val pausedUntil: mayday.shared.Ts? = null,
    val pauseReason: String = "",
)

@Serializable
data class McpServerInput(
    val name: String,
    val url: String,
    val headerName: String = "Authorization",
    val credential: String? = null,
    val toolAllowlist: List<String> = emptyList(),
    val enabled: Boolean = true,
    val useAgentIdentity: Boolean = false,
)

@Serializable
data class McpServerView(
    val id: String,
    val name: String,
    val url: String,
    val headerName: String,
    val hasCredential: Boolean,
    val toolAllowlist: List<String>,
    val enabled: Boolean,
    val useAgentIdentity: Boolean = false,
)

/** The stored LLM settings with the environment applied, and the plain secrets. */
data class EffectiveLlm(val config: LlmConfig, val apiKey: String, val clientSecret: String, val managed: List<String>)

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
    private val env: LlmEnvironment = LlmEnvironment(),
    concurrency: Int = 16,
    private val credentials: CredentialProviderFactory = CredentialProviderFactory { StaticCredential(it.staticSecret) },
    private val telemetry: Telemetry = Telemetry.noop,
    private val budgetPause: Duration = DEFAULT_COOLDOWN,
) : AgentIdentityLookup, AlertAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("agent-runs"))
    private val permits = Semaphore(concurrency)
    private val delegationTtl = Duration.ofHours(1)

    private data class Pause(val until: Instant, val reason: String)
    @Volatile private var pause: Pause? = null

    override suspend fun find(agentId: String): AgentIdentity? =
        agents.get(agentId)?.let { AgentIdentity(it.id, it.name, it.role, it.enabled) }

    suspend fun ensureInternalAgent() {
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

    suspend fun list(by: Principal): List<AgentConfig> = Access.requireRead(by).let { agents.list().sortedBy { it.name.lowercase() }.map(::redact) }
    suspend fun get(by: Principal, id: String) = Access.requireRead(by).let { redact(agents.get(id) ?: throw NotFound("Agent")) }

    private fun redact(a: AgentConfig) = a.copy(secretSealed = if (a.secretSealed.isEmpty()) "" else "set")

    suspend fun create(by: Principal, input: AgentInput): AgentCredentials {
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

    suspend fun update(by: Principal, id: String, input: AgentInput): AgentConfig {
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

    suspend fun rotateCredentials(by: Principal, id: String): AgentCredentials {
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

    suspend fun delete(by: Principal, id: String) {
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

    suspend fun llm(by: Principal): LlmView = Access.requireRead(by).let { view(effectiveLlm()) }

    suspend fun effectiveLlm(): EffectiveLlm = merge(config.llm() ?: LlmConfig())

    private fun merge(c: LlmConfig): EffectiveLlm {
        val e = env
        val merged = c.copy(
            provider = e.provider ?: c.provider,
            model = e.model ?: c.model,
            baseUrl = e.baseUrl ?: c.baseUrl,
            effort = e.effort ?: c.effort,
            enabled = e.enabled ?: c.enabled,
            authStyle = e.authStyle ?: c.authStyle,
            credential = e.credential ?: c.credential,
            tokenUrl = e.tokenUrl ?: c.tokenUrl,
            issuer = e.issuer ?: c.issuer,
            grant = e.grant ?: c.grant,
            clientAuth = e.clientAuth ?: c.clientAuth,
            clientId = e.clientId ?: c.clientId,
            assertionFile = e.assertionFile ?: c.assertionFile,
            tokenFile = e.tokenFile ?: c.tokenFile,
            scope = e.scope ?: c.scope,
            tokenParams = e.tokenParams ?: c.tokenParams,
            headers = e.headers ?: c.headers,
        )
        val apiKey = e.apiKey ?: if (c.apiKeySealed.isNotEmpty()) box.open(c.apiKeySealed) else ""
        val clientSecret = e.clientSecret ?: if (c.clientSecretSealed.isNotEmpty()) box.open(c.clientSecretSealed) else ""
        return EffectiveLlm(merged, apiKey, clientSecret, e.managedFields)
    }

    private fun view(e: EffectiveLlm): LlmView {
        val c = e.config
        val p = currentPause()
        return LlmView(
            provider = c.provider, model = c.model, baseUrl = c.baseUrl, effort = c.effort, enabled = c.enabled,
            hasApiKey = e.apiKey.isNotEmpty(), ready = llmReady(e),
            authStyle = c.effectiveAuthStyle, credential = c.credential, tokenUrl = c.tokenUrl,
            issuer = c.issuer, grant = c.grant, clientAuth = c.clientAuth, clientId = c.clientId,
            hasClientSecret = e.clientSecret.isNotEmpty(), assertionFile = c.assertionFile, tokenFile = c.tokenFile, scope = c.scope,
            tokenParams = c.tokenParams, headers = c.headers, managedFields = e.managed,
            pausedUntil = p?.until, pauseReason = p?.reason.orEmpty(),
        )
    }

    suspend fun updateLlm(by: Principal, input: LlmInput): LlmView {
        Access.requireAdmin(by)
        val cur = config.llm() ?: LlmConfig()
        val managed = env.managedFields.toSet()
        fun <T> check(field: String, value: T?) { if (value != null && field in managed) throw Invalid("'$field' is set by the environment and cannot be changed here") }
        check("provider", input.provider); check("model", input.model); check("apiKey", input.apiKey); check("baseUrl", input.baseUrl)
        check("effort", input.effort); check("enabled", input.enabled); check("authStyle", input.authStyle); check("credential", input.credential)
        check("tokenUrl", input.tokenUrl); check("issuer", input.issuer); check("grant", input.grant)
        check("clientAuth", input.clientAuth); check("tokenParams", input.tokenParams); check("clientId", input.clientId); check("clientSecret", input.clientSecret)
        check("assertionFile", input.assertionFile); check("tokenFile", input.tokenFile); check("scope", input.scope)
        check("headers", input.headers)
        val effort = input.effort ?: cur.effort
        if (effort !in setOf("low", "medium", "high", "xhigh", "max")) throw Invalid("Effort must be low, medium, high, xhigh or max")
        val provider = input.provider?.let { LlmApi.of(it).id } ?: cur.provider
        val baseUrl = input.baseUrl?.trim() ?: cur.baseUrl
        if (baseUrl.isNotEmpty()) requireHttpUrl(baseUrl, "The base URL")
        val tokenUrl = input.tokenUrl?.trim() ?: cur.tokenUrl
        if (tokenUrl.isNotEmpty()) requireHttpUrl(tokenUrl, "The token URL")
        val issuer = input.issuer?.trim() ?: cur.issuer
        if (issuer.isNotEmpty()) requireHttpUrl(issuer, "The issuer")
        val tokenParams = input.tokenParams?.let(::tokenParams) ?: cur.tokenParams
        val headerName = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
        val headers = input.headers?.mapKeys { it.key.trim() }?.filterKeys { it.isNotEmpty() } ?: cur.headers
        headers.keys.forEach { if (!headerName.matches(it)) throw Invalid("'$it' is not a valid header name") }
        val next = cur.copy(
            provider = provider,
            model = input.model?.trim()?.ifEmpty { null } ?: cur.model,
            apiKeySealed = sealed(input.apiKey, cur.apiKeySealed),
            baseUrl = baseUrl,
            effort = effort,
            enabled = input.enabled ?: cur.enabled,
            authStyle = input.authStyle ?: cur.authStyle,
            credential = input.credential ?: cur.credential,
            tokenUrl = tokenUrl,
            issuer = issuer,
            grant = input.grant ?: cur.grant,
            clientAuth = input.clientAuth ?: cur.clientAuth,
            clientId = input.clientId?.trim() ?: cur.clientId,
            clientSecretSealed = sealed(input.clientSecret, cur.clientSecretSealed),
            assertionFile = input.assertionFile?.trim() ?: cur.assertionFile,
            tokenFile = input.tokenFile?.trim() ?: cur.tokenFile,
            scope = input.scope?.trim() ?: cur.scope,
            tokenParams = tokenParams,
            headers = headers,
        )
        config.saveLlm(next)
        pause = null
        return view(merge(next))
    }

    private fun sealed(input: String?, cur: String) = when {
        input == null -> cur
        input.isBlank() -> ""
        else -> box.seal(input.trim())
    }

    private fun tokenParams(input: Map<String, String>): Map<String, String> {
        val params = input.mapKeys { it.key.trim() }.filterKeys { it.isNotEmpty() }
        params.keys.forEach { if (it.lowercase() in RESERVED_TOKEN_PARAMS) throw Invalid("The token parameter '$it' is set by Mayday") }
        return params
    }

    private fun requireHttpUrl(url: String, what: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw Invalid("$what must be an http(s) URL")
    }

    private fun llmReady(e: EffectiveLlm): Boolean {
        val c = e.config
        if (!c.enabled) return false
        return spec(e).complete
    }

    private fun spec(e: EffectiveLlm): CredentialSpec {
        val c = e.config
        return CredentialSpec(
            mode = c.credential,
            staticSecret = e.apiKey,
            tokenUrl = c.tokenUrl,
            issuer = c.issuer,
            grant = c.grant,
            clientAuth = c.clientAuth,
            clientId = c.clientId,
            clientSecret = e.clientSecret,
            assertionFile = c.assertionFile,
            tokenFile = c.tokenFile,
            scope = c.scope,
            tokenParams = c.tokenParams,
        )
    }

    suspend fun internalReady(): Boolean = llmReady(effectiveLlm()) && agents.get(INTERNAL_AGENT_ID)?.enabled == true

    /** Identity headers of the agent, for the AI gateway and the MCP servers that use the agent identity. */
    suspend fun gatewayAuth(): GatewayAuth {
        val e = effectiveLlm()
        val c = e.config
        val provider = if (c.credential == CredentialMode.NONE) null else credentials.create(spec(e))
        return GatewayAuth(c.effectiveAuthStyle, provider, c.headers)
    }

    private fun currentPause(): Pause? = pause?.takeIf { it.until.isAfter(clock.now()) }

    /** After HTTP 429 the internal agent makes no LLM call until the pause ends or an admin changes the LLM settings. */
    private fun pauseFor(e: BudgetExhausted) {
        val wait = (e.retryAfter ?: budgetPause).coerceIn(Duration.ofSeconds(1), Duration.ofHours(24))
        pause = Pause(clock.now().plus(wait), e.message ?: "HTTP 429")
    }

    private suspend fun llmProvider(): LlmProvider {
        val e = effectiveLlm()
        if (!llmReady(e)) throw Unavailable("The LLM provider is disabled or has no credential")
        currentPause()?.let { throw Unavailable("The AI gateway budget of the agent is used up. The agent is paused until ${it.until}.") }
        return llmFactory.create(e.config, gatewayAuth())
    }

    suspend fun mcpServers(by: Principal): List<McpServerView> {
        Access.requireRead(by)
        return mcpServers.list().sortedBy { it.name.lowercase() }.map(::mcpView)
    }

    suspend fun saveMcpServer(by: Principal, id: String?, input: McpServerInput): McpServerView {
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
            useAgentIdentity = input.useAgentIdentity,
        )
        mcpServers.save(s)
        events.publish(DomainEvent("mcp_server.changed", s.id, clock.now()))
        return mcpView(s)
    }

    private fun mcpView(s: McpServerConfig) =
        McpServerView(s.id, s.name, s.url, s.headerName, s.credentialSealed.isNotEmpty(), s.toolAllowlist, s.enabled, s.useAgentIdentity)

    suspend fun deleteMcpServer(by: Principal, id: String) {
        Access.requireAdmin(by)
        mcpServers.delete(id)
        events.publish(DomainEvent("mcp_server.changed", id, clock.now()))
    }

    // ---- runs ----

    suspend fun runsFor(by: Principal, subjectId: String): List<AgentRun> = Access.requireRead(by).let { runs.bySubject(subjectId).sortedByDescending { it.createdAt } }
    suspend fun recentRuns(by: Principal, limit: Int) = Access.requireRead(by).let { runs.recent(limit.coerceIn(1, 200)) }
    suspend fun run(by: Principal, id: String): AgentRun = Access.requireRead(by).let { runs.get(id) ?: throw NotFound("Agent run") }

    /** Agents that can perform [action] right now, the built-in agent first. */
    suspend fun eligible(action: AgentAction): List<AgentConfig> = agents.list()
        .filter { it.enabled && action in it.actions && (it.kind == AgentKind.EXTERNAL || internalReady()) }
        .sortedBy { if (it.kind == AgentKind.INTERNAL) 0 else 1 }
        .map(::redact)

    /**
     * A human presses a button. The agent receives a delegated token and acts on behalf of that human,
     * so the human's RBAC applies to every change the agent makes.
     */
    suspend fun trigger(by: Principal, action: AgentAction, subjectKind: SubjectKind, subjectRef: String, agentId: String?, prompt: String): AgentRun {
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
        if (agent.kind == AgentKind.INTERNAL) currentPause()?.let {
            throw Unavailable("The AI gateway budget of ${agent.name} is used up. Try again after ${it.until} or use another agent.")
        }
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

    /** Returns at once. Alert ingestion and incident creation never wait for agents. */
    override suspend fun onAlertCreated(alert: Alert) {
        val link = telemetry.propagationHeaders()["traceparent"]
        scope.launch {
            runCatching { telemetry.span("agent.alert_triggers", mapOf("mayday.alert.id" to alert.id, LINK to link)) { startTriggered(alert) } }
        }
    }

    private suspend fun startTriggered(alert: Alert) {
        val teams = listOf(alert.teamId)
        for (agent in agents.list()) {
            if (!agent.enabled) continue
            if (agent.kind == AgentKind.INTERNAL && !internalReady()) continue
            if (agent.kind == AgentKind.INTERNAL && currentPause() != null) continue
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

    /** Each run is its own trace. Its root span links to the trace that started it. */
    private suspend fun start(agent: AgentConfig, run: AgentRun) {
        publish(run)
        val link = telemetry.propagationHeaders()["traceparent"]
        when {
            agent.kind == AgentKind.INTERNAL -> scope.launch { permits.withPermit { executeInternal(agent, run.id, link) } }
            agent.delivery == Delivery.PUSH -> scope.launch { push(agent, run.id, link) }
            else -> Unit
        }
    }

    private suspend fun push(agent: AgentConfig, runId: String, link: String?) {
        val run = runs.get(runId) ?: return
        telemetry.span("agent.dispatch ${agent.name}", runAttributes(agent, run) + (LINK to link), SpanKind.PRODUCER) { span -> pushTraced(agent, run, span) }
    }

    private suspend fun pushTraced(agent: AgentConfig, run: AgentRun, span: SpanScope) {
        val runId = run.id
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
            transition(runId) { it.copy(status = RunStatus.DISPATCHED, startedAt = clock.now(), traceId = span.traceId) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            span.fail(e.message ?: e.javaClass.simpleName, e)
            finish(runId, RunStatus.FAILED, "", "Delivery failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun claim(by: Principal, limit: Int): List<ClaimedRun> {
        if (!by.isAgent || by.isDelegated) throw Forbidden("Only an agent identity can claim runs")
        val agent = agents.get(by.actor.id) ?: throw NotFound("Agent")
        return runs.claim(agent.id, limit.coerceIn(1, 50), clock.now()).map {
            publish(it)
            ClaimedRun(it, delegatedToken(agent, it), snapshot(it))
        }
    }

    suspend fun complete(by: Principal, runId: String, success: Boolean, output: String, error: String): AgentRun {
        if (!by.isAgent) throw Forbidden("Only the agent that owns the run can complete it")
        val run = runs.get(runId) ?: throw NotFound("Agent run")
        if (run.agentId != by.actor.id) throw Forbidden("This run belongs to another agent")
        if (run.status.terminal) throw Conflict("The run is already finished")
        return finish(runId, if (success) RunStatus.SUCCEEDED else RunStatus.FAILED, output, error)
    }

    suspend fun progress(by: Principal, runId: String, text: String): AgentRun {
        val run = runs.get(runId) ?: throw NotFound("Agent run")
        if (run.agentId != by.actor.id) throw Forbidden("This run belongs to another agent")
        if (run.status.terminal) throw Conflict("The run is already finished")
        val next = transition(runId) { it.copy(status = RunStatus.RUNNING, startedAt = it.startedAt ?: clock.now()) }
        if (text.isNotBlank()) note(run, text.trim(), Actor(ActorKind.AGENT, run.agentId, run.agentName, run.onBehalfOf))
        return next
    }

    suspend fun cancel(by: Principal, runId: String): AgentRun {
        Access.requireWrite(by)
        val run = runs.get(runId) ?: throw NotFound("Agent run")
        if (run.status.terminal) return run
        return finish(runId, RunStatus.CANCELLED, run.output, "Cancelled by ${by.actor.name}")
    }

    /** Mark runs that stopped reporting as failed so the queue does not grow without bound. */
    suspend fun reapStale(maxAge: Duration) {
        val limit = clock.now().minus(maxAge)
        runs.active().filter { (it.startedAt ?: it.createdAt).isBefore(limit) }.forEach {
            finish(it.id, RunStatus.FAILED, it.output, "The agent did not report a result in time")
        }
    }

    private suspend fun executeInternal(agent: AgentConfig, runId: String, link: String?) {
        val queued = runs.get(runId) ?: return
        val llm = effectiveLlm().config
        telemetry.span("invoke_agent ${agent.name}", runAttributes(agent, queued) + mapOf(
            "gen_ai.operation.name" to "invoke_agent",
            "gen_ai.provider.name" to llm.provider,
            "gen_ai.request.model" to llm.model,
            LINK to link,
        )) { span ->
            val run = transition(runId) { it.copy(status = RunStatus.RUNNING, startedAt = clock.now(), traceId = span.traceId) }
            try {
                val principal = principalFor(agent, run)
                val result = InternalAgent(llmProvider(), tools(), clock, telemetry, llm.model).run(agent, run, principal, snapshot(run))
                finish(runId, RunStatus.SUCCEEDED, result, "")
                span.set("mayday.run.status", RunStatus.SUCCEEDED.name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BudgetExhausted) {
                pauseFor(e)
                span.set("mayday.run.status", RunStatus.FAILED.name)
                span.set("error.type", "budget_exhausted")
                span.fail(e.message ?: "HTTP 429", e)
                finish(runId, RunStatus.FAILED, "", "${e.message} Mayday does not retry. The agent is paused until ${currentPause()?.until}.")
            } catch (e: Exception) {
                span.set("mayday.run.status", RunStatus.FAILED.name)
                span.fail(e.message ?: e.javaClass.simpleName, e)
                finish(runId, RunStatus.FAILED, "", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun runAttributes(agent: AgentConfig, run: AgentRun): Map<String, Any?> = mapOf(
        "gen_ai.agent.id" to agent.id,
        "gen_ai.agent.name" to agent.name,
        "gen_ai.conversation.id" to run.id,
        "mayday.run.id" to run.id,
        "mayday.run.action" to run.action.name,
        "mayday.run.trigger" to run.trigger.name,
        "mayday.run.subject_kind" to run.subjectKind.name,
        "mayday.run.subject_id" to run.subjectId,
        "mayday.run.subject" to run.subjectLabel,
        "mayday.run.on_behalf_of" to run.onBehalfOf,
    )

    private suspend fun principalFor(agent: AgentConfig, run: AgentRun): Principal {
        val user = run.onBehalfOf?.let { runCatching { identity().user(it) }.getOrNull() }
        val actor = Actor(ActorKind.AGENT, agent.id, agent.name, onBehalfOf = user?.id)
        val scopes = if (user != null) Principal.ALL else scopesFor(agent.role)
        return Principal(actor, user, agent.role, scopes, run.id)
    }

    private suspend fun delegatedToken(agent: AgentConfig, run: AgentRun): String? {
        val userId = run.onBehalfOf ?: return null
        val user = runCatching { identity().user(userId) }.getOrNull() ?: return null
        return identity().delegate(user, AgentIdentity(agent.id, agent.name, agent.role, agent.enabled), run.id, delegationTtl, Principal.ALL)
    }

    private suspend fun finish(runId: String, status: RunStatus, output: String, error: String): AgentRun {
        val done = transition(runId) { it.copy(status = status, output = output.take(100_000), error = error.take(4000), finishedAt = clock.now()) }
        val agentActor = Actor(ActorKind.AGENT, done.agentId, done.agentName, done.onBehalfOf)
        when (status) {
            RunStatus.SUCCEEDED -> if (output.isNotBlank()) note(done, output.take(20_000), agentActor)
            RunStatus.FAILED -> note(done, "${done.agentName} failed: ${error.take(500)}", agentActor)
            else -> Unit
        }
        return done
    }

    private suspend fun transition(runId: String, f: (AgentRun) -> AgentRun): AgentRun {
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

    private suspend fun note(run: AgentRun, text: String, actor: Actor = Actor.system) {
        when (run.subjectKind) {
            SubjectKind.INCIDENT -> incidents.systemEntry(run.subjectId, EntryKind.AGENT, actor, text, mapOf("runId" to run.id))
            SubjectKind.ALERT -> runCatching { alerts.addUpdate(Principal.system, run.subjectId, text) }
        }
    }

    private suspend fun subject(by: Principal, kind: SubjectKind, ref: String): Pair<String, String> = when (kind) {
        SubjectKind.INCIDENT -> incidents.get(by, ref).let { it.id to it.key }
        SubjectKind.ALERT -> alerts.get(by, ref).let { it.id to it.title.take(80) }
    }

    private suspend fun snapshot(run: AgentRun): JsonObject = when (run.subjectKind) {
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

    private suspend fun publish(run: AgentRun) =
        events.publish(DomainEvent("agent_run.updated", run.id, clock.now(), data = mapOf("subjectId" to run.subjectId, "status" to run.status.name)))

    fun shutdown() = scope.cancel()
}
