package mayday

import mayday.adapters.auth.OidcClient
import mayday.adapters.auth.OidcSettings
import mayday.adapters.auth.Sessions
import mayday.adapters.dispatch.WebhookDispatcher
import mayday.adapters.http.Api
import mayday.adapters.http.Metrics
import mayday.adapters.http.Server
import mayday.adapters.http.SseHub
import mayday.adapters.http.StaticFiles
import mayday.adapters.llm.AnthropicFactory
import mayday.adapters.mcp.McpGateway
import mayday.adapters.mcp.McpServer
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.PgEventBus
import mayday.adapters.postgres.Pool
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
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

const val VERSION = "0.1.0"

data class Config(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val publicUrl: String = "http://localhost:8080",
    val dataDir: String? = "./data",
    val databaseUrl: String? = null,
    val databaseUser: String? = null,
    val databasePassword: String? = null,
    val databasePool: Int = 20,
    val secretKey: String = "",
    val devLogin: Boolean = false,
    val demo: Boolean = false,
    val adminEmails: Set<String> = emptySet(),
    val oidcIssuer: String? = null,
    val oidcClientId: String = "",
    val oidcClientSecret: String = "",
    val oidcScopes: String = "openid email profile",
    val oidcAudiences: Set<String> = emptySet(),
    val anthropicApiKey: String? = null,
    val webDir: String? = null,
    val sessionHours: Long = 12,
    val maxSseClients: Int = 100_000,
    val agentConcurrency: Int = 16,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun s(k: String) = env[k]?.takeIf { it.isNotBlank() }
            fun b(k: String) = s(k)?.lowercase() in setOf("1", "true", "yes", "on")
            val port = s("PORT")?.toInt() ?: s("MAYDAY_PORT")?.toInt() ?: 8080
            return Config(
                host = s("MAYDAY_HOST") ?: "0.0.0.0",
                port = port,
                publicUrl = (s("MAYDAY_PUBLIC_URL") ?: "http://localhost:$port").trimEnd('/'),
                dataDir = if (s("MAYDAY_DATA_DIR") == "none") null else s("MAYDAY_DATA_DIR") ?: "./data",
                databaseUrl = s("MAYDAY_DATABASE_URL")?.let { if (it.startsWith("jdbc:")) it else "jdbc:$it" },
                databaseUser = s("MAYDAY_DATABASE_USER"),
                databasePassword = s("MAYDAY_DATABASE_PASSWORD"),
                databasePool = s("MAYDAY_DATABASE_POOL")?.toInt() ?: 20,
                secretKey = s("MAYDAY_SECRET_KEY") ?: "",
                devLogin = b("MAYDAY_DEV_LOGIN"),
                demo = b("MAYDAY_DEMO"),
                adminEmails = s("MAYDAY_ADMIN_EMAILS")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
                oidcIssuer = s("MAYDAY_OIDC_ISSUER"),
                oidcClientId = s("MAYDAY_OIDC_CLIENT_ID") ?: "",
                oidcClientSecret = s("MAYDAY_OIDC_CLIENT_SECRET") ?: "",
                oidcScopes = s("MAYDAY_OIDC_SCOPES") ?: "openid email profile",
                oidcAudiences = s("MAYDAY_OIDC_AUDIENCES")?.split(',')?.map { it.trim() }?.toSet() ?: emptySet(),
                anthropicApiKey = s("ANTHROPIC_API_KEY"),
                webDir = s("MAYDAY_WEB_DIR"),
                sessionHours = s("MAYDAY_SESSION_HOURS")?.toLong() ?: 12,
                maxSseClients = s("MAYDAY_MAX_LIVE_CONNECTIONS")?.toInt() ?: 100_000,
                agentConcurrency = s("MAYDAY_AGENT_CONCURRENCY")?.toInt() ?: 16,
            )
        }
    }
}

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

/** Composition root: builds the bounded contexts on top of the ports. */
class App(val config: Config, val ports: Ports) {
    private val key = config.secretKey.ifEmpty { "insecure-development-key-change-me" }.toByteArray()
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
        ports.llm, ports.dispatcher, { tools }, ports.events, ports.clock, config.publicUrl, config.anthropicApiKey, config.agentConcurrency,
    )
    val reports = ReportService(ports.incidents, ports.alerts, directory)
    val maydayTools: MaydayTools
    val mcp: McpServer

    init {
        identity = IdentityService(ports.users, ports.tokens, ports.teams, agents, signer, ports.clock, config.adminEmails)
        maydayTools = MaydayTools(incidents, alerts, directory, policy, { identity }, { agents })
        tools = CompositeCatalog(listOf(maydayTools, McpGateway(ports.mcpServers, box)))
        mcp = McpServer(maydayTools, VERSION)
        alerts.automation = agents
        agents.ensureInternalAgent()
        ports.events.subscribe { if (it.type == "setting.changed") policy.invalidate() }
    }
}

fun main() {
    val log = Logger.getLogger("mayday")
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1\$tFT%1\$tT.%1\$tLZ %4\$s %3\$s: %5\$s%6\$s%n")
    val config = Config.fromEnv()
    if (config.secretKey.isEmpty()) log.warning("MAYDAY_SECRET_KEY is not set. Sessions and stored secrets use a development key.")
    val ports = if (config.databaseUrl != null) {
        val pool = Pool(config.databaseUrl, config.databaseUser, config.databasePassword, config.databasePool)
        Ports.postgres(PostgresStore(pool), PgEventBus(pool))
    } else {
        Ports.memory(MemoryStore(config.dataDir?.let { Path.of(it) }))
    }
    val app = App(config, ports)
    if (config.demo) Demo.seed(app)

    val oidc = config.oidcIssuer?.let {
        OidcClient(OidcSettings(it, config.oidcClientId, config.oidcClientSecret, config.oidcScopes, "${config.publicUrl}/auth/callback", config.oidcAudiences))
    }
    val sse = SseHub(ports.events, config.maxSseClients)
    val api = Api(app.identity, app.directory, app.policy, app.incidents, app.alerts, app.agents, app.reports, app.mcp,
        Sessions(app.signer, Duration.ofHours(config.sessionHours), config.publicUrl.startsWith("https")),
        oidc, config.devLogin || (oidc == null && config.demo), config.publicUrl, sse)
    val server = Server(api, StaticFiles(config.webDir?.let { Path.of(it).toAbsolutePath().normalize() }), Metrics(), sse) { true }
    val port = server.start(config.host, config.port)

    val maintenance = Executors.newSingleThreadScheduledExecutor()
    maintenance.scheduleAtFixedRate({ runCatching { app.agents.reapStale(Duration.ofMinutes(30)) } }, 1, 5, TimeUnit.MINUTES)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(); maintenance.shutdownNow(); app.agents.shutdown(); ports.close()
    })
    val storage = if (config.databaseUrl != null) "PostgreSQL" else config.dataDir?.let { "journal in $it" } ?: "memory only"
    log.info("Mayday $VERSION listens on port $port ($storage). Open ${config.publicUrl}")
    if (oidc == null && !config.devLogin && !config.demo) log.warning("No sign-in method is configured. Set MAYDAY_OIDC_ISSUER or MAYDAY_DEV_LOGIN=true.")
}
