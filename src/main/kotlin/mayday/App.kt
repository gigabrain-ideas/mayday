package mayday

import mayday.adapters.dispatch.WebhookDispatcher
import mayday.adapters.credentials.Credentials
import mayday.adapters.llm.LlmProviders
import mayday.adapters.mcp.McpGateway
import mayday.adapters.mcp.McpServer
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.PostgresStore
import mayday.agents.AgentDispatcher
import mayday.agents.AgentRepository
import mayday.agents.AgentService
import mayday.agents.ConfigRepository
import mayday.agents.CredentialProviderFactory
import mayday.agents.LlmEnvironment
import mayday.agents.LlmProviderFactory
import mayday.agents.McpServerRepository
import mayday.agents.RunRepository
import mayday.alerting.AlertRepository
import mayday.alerting.AlertService
import mayday.directory.DirectoryService
import mayday.directory.ProjectRepository
import mayday.directory.TeamRepository
import mayday.identity.IdentityService
import mayday.identity.TokenRepository
import mayday.identity.UserRepository
import mayday.incidents.IncidentRepository
import mayday.incidents.IncidentService
import mayday.incidents.TimelineRepository
import mayday.policy.ChangeRequestRepository
import mayday.policy.PolicyService
import mayday.policy.SettingRepository
import mayday.oncall.OnCallService
import mayday.oncall.OverrideRepository
import mayday.oncall.ScheduleRepository
import mayday.escalation.EscalationPolicyRepository
import mayday.escalation.EscalationRepository
import mayday.escalation.EscalationService
import mayday.escalation.Pager
import mayday.provisioning.GroupRepository
import mayday.provisioning.GroupSync
import mayday.provisioning.GroupSyncSettings
import mayday.provisioning.ProvisioningService
import mayday.reporting.ReportService
import mayday.shared.Clock
import mayday.shared.EventBus
import mayday.shared.InProcessEventBus
import mayday.shared.SecretBox
import mayday.shared.Telemetry
import mayday.shared.TokenSigner
import mayday.agents.ToolCatalog
import mayday.audit.AuditChain
import mayday.audit.AuditLog
import mayday.audit.AuditSettings
import mayday.audit.AuditSink
import mayday.tools.AuditedCatalog
import mayday.tools.CompositeCatalog
import mayday.tools.MaydayTools

const val VERSION = "0.1.0"

/** Settings that the bounded contexts need. The Spring adapter fills them from the environment. */
data class AppSettings(
    val publicUrl: String = "http://localhost:8080",
    val secretKey: String = "",
    val adminEmails: Set<String> = emptySet(),
    val llm: LlmEnvironment = LlmEnvironment(),
    val agentConcurrency: Int = 16,
    val groups: GroupSyncSettings = GroupSyncSettings(),
    val audit: AuditSettings = AuditSettings(),
    /** MAYDAY_AUDIT_KEY. Empty: derived from [secretKey]. */
    val auditKey: String = "",
)

/** Every outbound port the application needs. Adapters provide one implementation each. */
class Ports(
    val projects: ProjectRepository,
    val teams: TeamRepository,
    val users: UserRepository,
    val tokens: TokenRepository,
    val settings: SettingRepository,
    val changeRequests: ChangeRequestRepository,
    val incidents: IncidentRepository,
    val timeline: TimelineRepository,
    val alerts: AlertRepository,
    val agents: AgentRepository,
    val runs: RunRepository,
    val mcpServers: McpServerRepository,
    val config: ConfigRepository,
    val schedules: ScheduleRepository,
    val overrides: OverrideRepository,
    val escalationPolicies: EscalationPolicyRepository,
    val escalations: EscalationRepository,
    val groups: GroupRepository,
    val events: EventBus,
    val llm: LlmProviderFactory = LlmProviders,
    val dispatcher: AgentDispatcher = WebhookDispatcher(),
    val clock: Clock = Clock.system,
    val close: () -> Unit = {},
    val credentials: CredentialProviderFactory = Credentials(),
    val telemetry: Telemetry = Telemetry.noop,
    val audit: List<AuditSink> = emptyList(),
    val pager: Pager = Pager.none,
) {
    fun with(
        telemetry: Telemetry = this.telemetry,
        llm: LlmProviderFactory = this.llm,
        dispatcher: AgentDispatcher = this.dispatcher,
        credentials: CredentialProviderFactory = this.credentials,
        audit: List<AuditSink> = this.audit,
        clock: Clock = this.clock,
        pager: Pager = this.pager,
    ) = Ports(
        projects, teams, users, tokens, settings, changeRequests, incidents, timeline, alerts, agents, runs, mcpServers, config,
        schedules, overrides, escalationPolicies, escalations, groups, events, llm, dispatcher, clock, close, credentials, telemetry, audit, pager,
    )

    companion object {
        fun memory(store: MemoryStore, events: EventBus = InProcessEventBus()) = Ports(
            store.projects, store.teams, store.users, store.tokens, store.settings, store.changeRequests,
            store.incidents, store.timeline, store.alerts, store.agents, store.runs, store.mcpServers, store.config,
            store.schedules, store.overrides, store.escalationPolicies, store.escalations, store.groups, events,
            close = { store.close() },
        )

        fun postgres(store: PostgresStore, events: EventBus) = Ports(
            store.projects, store.teams, store.users, store.tokens, store.settings, store.changeRequests,
            store.incidents, store.timeline, store.alerts, store.agents, store.runs, store.mcpServers, store.config,
            store.schedules, store.overrides, store.escalationPolicies, store.escalations, store.groups, events,
            close = { (events as? AutoCloseable)?.close(); store.pool.close() },
        )
    }
}

/** Composition of the bounded contexts on top of the ports. It has no framework code. */
class App(val settings: AppSettings, val ports: Ports) {
    private val key = settings.secretKey.ifEmpty { "insecure-development-key-change-me" }.toByteArray()
    val signer = TokenSigner(key)
    val audit = AuditLog(
        ports.audit, settings.audit, AuditChain.key(String(key), settings.auditKey), ports.clock,
        { ports.telemetry.currentTraceId() }, mapOf("version" to VERSION),
    )
    val box = SecretBox(key)
    val directory = DirectoryService(ports.projects, ports.teams, ports.clock)
    val policy = PolicyService(ports.settings, ports.changeRequests, directory, ports.events, ports.clock, audit)
    val incidents = IncidentService(ports.incidents, ports.timeline, directory, policy, ports.events, ports.clock, ports.telemetry)
    val alerts = AlertService(ports.alerts, incidents, directory, policy, ports.events, ports.clock, ports.telemetry)
    lateinit var identity: IdentityService
    lateinit var tools: ToolCatalog
    val agents: AgentService = AgentService(
        ports.agents, ports.runs, ports.mcpServers, ports.config, incidents, alerts, { identity }, policy, box,
        ports.llm, ports.dispatcher, { tools }, ports.events, ports.clock, settings.publicUrl, settings.llm, settings.agentConcurrency,
        ports.credentials, ports.telemetry, audit = audit,
    )
    val reports = ReportService(ports.incidents, ports.alerts, directory)
    val onCall = OnCallService(ports.schedules, ports.overrides, ports.users, directory, ports.events, ports.clock)
    val escalation = EscalationService(
        ports.escalationPolicies, ports.escalations, ports.alerts, onCall, ports.users, directory, policy, ports.events, ports.clock,
        settings.publicUrl, ports.pager,
    )
    val groupSync = GroupSync(ports.users, directory, settings.groups, audit) { onCall }
    val provisioning = ProvisioningService(ports.users, ports.groups, directory, groupSync, ports.clock, settings.adminEmails)
    val maydayTools: MaydayTools
    val mcp: McpServer

    init {
        identity = IdentityService(ports.users, ports.tokens, ports.teams, agents, signer, ports.clock, settings.adminEmails, policy, audit)
        identity.membership = onCall
        identity.groupSync = groupSync
        maydayTools = MaydayTools(incidents, alerts, directory, policy, { identity }, { agents }, onCall)
        tools = AuditedCatalog(CompositeCatalog(listOf(maydayTools, McpGateway(ports.mcpServers, box, { agents.gatewayAuth() }, ports.telemetry))), audit)
        mcp = McpServer(AuditedCatalog(maydayTools, audit), VERSION, ports.telemetry, audit)
        alerts.automation = agents
        alerts.lifecycle = escalation
        policy.listener = identity
        ports.events.subscribe { if (it.type == "setting.changed") policy.invalidate() }
    }

    suspend fun start() = agents.ensureInternalAgent()

    fun stop() {
        agents.shutdown()
        audit.close()
        ports.close()
    }
}
