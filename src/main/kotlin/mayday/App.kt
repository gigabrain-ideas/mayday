package mayday

import mayday.adapters.dispatch.WebhookDispatcher
import mayday.adapters.llm.AnthropicFactory
import mayday.adapters.mcp.McpGateway
import mayday.adapters.mcp.McpServer
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.PostgresStore
import mayday.agents.AgentDispatcher
import mayday.agents.AgentRepository
import mayday.agents.AgentService
import mayday.agents.ConfigRepository
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
import mayday.reporting.ReportService
import mayday.shared.Clock
import mayday.shared.EventBus
import mayday.shared.InProcessEventBus
import mayday.shared.SecretBox
import mayday.shared.TokenSigner
import mayday.tools.CompositeCatalog
import mayday.tools.MaydayTools

const val VERSION = "0.1.0"

/** Settings that the bounded contexts need. The Spring adapter fills them from the environment. */
data class AppSettings(
    val publicUrl: String = "http://localhost:8080",
    val secretKey: String = "",
    val adminEmails: Set<String> = emptySet(),
    val anthropicApiKey: String? = null,
    val agentConcurrency: Int = 16,
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
    val events: EventBus,
    val llm: LlmProviderFactory = AnthropicFactory,
    val dispatcher: AgentDispatcher = WebhookDispatcher(),
    val clock: Clock = Clock.system,
    val close: () -> Unit = {},
) {
    companion object {
        fun memory(store: MemoryStore, events: EventBus = InProcessEventBus()) = Ports(
            store.projects, store.teams, store.users, store.tokens, store.settings, store.changeRequests,
            store.incidents, store.timeline, store.alerts, store.agents, store.runs, store.mcpServers, store.config, events,
            close = { store.close() },
        )

        fun postgres(store: PostgresStore, events: EventBus) = Ports(
            store.projects, store.teams, store.users, store.tokens, store.settings, store.changeRequests,
            store.incidents, store.timeline, store.alerts, store.agents, store.runs, store.mcpServers, store.config, events,
            close = { (events as? AutoCloseable)?.close(); store.pool.close() },
        )
    }
}

/** Composition of the bounded contexts on top of the ports. It has no framework code. */
class App(val settings: AppSettings, val ports: Ports) {
    private val key = settings.secretKey.ifEmpty { "insecure-development-key-change-me" }.toByteArray()
    val signer = TokenSigner(key)
    val box = SecretBox(key)
    val directory = DirectoryService(ports.projects, ports.teams, ports.clock)
    val policy = PolicyService(ports.settings, ports.changeRequests, directory, ports.events, ports.clock)
    val incidents = IncidentService(ports.incidents, ports.timeline, directory, policy, ports.events, ports.clock)
    val alerts = AlertService(ports.alerts, incidents, directory, policy, ports.events, ports.clock)
    lateinit var identity: IdentityService
    lateinit var tools: CompositeCatalog
    val agents: AgentService = AgentService(
        ports.agents, ports.runs, ports.mcpServers, ports.config, incidents, alerts, { identity }, policy, box,
        ports.llm, ports.dispatcher, { tools }, ports.events, ports.clock, settings.publicUrl, settings.anthropicApiKey, settings.agentConcurrency,
    )
    val reports = ReportService(ports.incidents, ports.alerts, directory)
    val maydayTools: MaydayTools
    val mcp: McpServer

    init {
        identity = IdentityService(ports.users, ports.tokens, ports.teams, agents, signer, ports.clock, settings.adminEmails)
        maydayTools = MaydayTools(incidents, alerts, directory, policy, { identity }, { agents })
        tools = CompositeCatalog(listOf(maydayTools, McpGateway(ports.mcpServers, box)))
        mcp = McpServer(maydayTools, VERSION)
        alerts.automation = agents
        ports.events.subscribe { if (it.type == "setting.changed") policy.invalidate() }
    }

    suspend fun start() = agents.ensureInternalAgent()

    fun stop() {
        agents.shutdown()
        ports.close()
    }
}
