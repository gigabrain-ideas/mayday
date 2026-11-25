package mayday

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mayday.adapters.auth.Authenticator
import mayday.adapters.auth.OidcClient
import mayday.adapters.auth.OidcSettings
import mayday.adapters.auth.Sessions
import mayday.adapters.credentials.Credentials
import mayday.adapters.dispatch.WebhookDispatcher
import mayday.adapters.llm.LlmProviders
import mayday.adapters.telemetry.OtelTelemetry
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.PgEventBus
import mayday.adapters.postgres.Pool
import mayday.adapters.postgres.PostgresStore
import mayday.agents.AuthStyle
import mayday.agents.ClientAuth
import mayday.agents.CredentialMode
import mayday.agents.LlmApi
import mayday.agents.LlmEnvironment
import mayday.agents.OAuthGrant
import mayday.agents.RESERVED_TOKEN_PARAMS
import mayday.shared.Telemetry
import io.opentelemetry.api.OpenTelemetry
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Logger

/** Bound from application.yaml, which maps the MAYDAY_* environment variables. */
@ConfigurationProperties("mayday")
data class MaydayProperties(
    val publicUrl: String = "http://localhost:8080",
    val dataDir: String = "./data",
    val database: Database = Database(),
    val secretKey: String = "",
    val devLogin: Boolean = false,
    val demo: Boolean = false,
    val adminEmails: String = "",
    val oidc: Oidc = Oidc(),
    val llm: Llm = Llm(),
    val webDir: String = "",
    val sessionHours: Long = 12,
    val maxLiveConnections: Int = 100_000,
    val agentConcurrency: Int = 16,
    val traceUrlTemplate: String = "",
    val telemetry: TelemetrySettings = TelemetrySettings(),
    val groups: Groups = Groups(),
    val audit: Audit = Audit(),
    val paging: Paging = Paging(),
) {
    /** Delivery of escalation pages. See docs/domains/escalation.md. */
    data class Paging(val webhookUrl: String = "", val webhookSecret: String = "", val webhookHeaders: String = "") {
        fun pager(): mayday.escalation.Pager = webhookUrl.takeIf { it.isNotBlank() }?.let {
            mayday.adapters.paging.WebhookPager(it.trim(), webhookSecret, webhookHeaders.takeIf { h -> h.isNotBlank() }?.let { h -> parsePairs(h, "MAYDAY_PAGING_WEBHOOK_HEADERS") }.orEmpty())
        } ?: mayday.escalation.Pager.none
    }

    /** Audit stream. See docs/domains/audit.md. */
    data class Audit(
        val sinks: String = "stdout",
        val reads: Boolean = true,
        val key: String = "",
        val instance: String = "",
        val capacity: Int = 65_536,
        val file: String = "",
        val http: Http = Http(),
        val loki: Loki = Loki(),
        val sentinel: Sentinel = Sentinel(),
    ) {
        data class Http(val url: String = "", val headers: String = "", val format: String = "json")
        data class Loki(val url: String = "", val tenant: String = "", val user: String = "", val password: String = "", val token: String = "", val labels: String = "")
        data class Sentinel(
            val endpoint: String = "",
            val dcrId: String = "",
            val stream: String = "Custom-MaydayAudit_CL",
            val tenantId: String = "",
            val clientId: String = "",
            val clientSecret: String = "",
            val assertionFile: String = "",
            val authority: String = "https://login.microsoftonline.com",
        )

        fun settings() = mayday.audit.AuditSettings(reads = reads, capacity = capacity.coerceAtLeast(1024),
            instance = instance.trim().ifEmpty { mayday.audit.AuditSettings.defaultInstance() })

        fun sinkConfig() = mayday.adapters.audit.AuditSinkConfig(
            sinks = csv(sinks),
            file = file,
            httpUrl = http.url, httpHeaders = http.headers.takeIf { it.isNotBlank() }?.let { parsePairs(it, "MAYDAY_AUDIT_HTTP_HEADERS") }.orEmpty(),
            httpFormat = http.format,
            lokiUrl = loki.url, lokiTenant = loki.tenant, lokiUser = loki.user, lokiPassword = loki.password, lokiToken = loki.token,
            lokiLabels = loki.labels.takeIf { it.isNotBlank() }?.let { parsePairs(it, "MAYDAY_AUDIT_LOKI_LABELS") }.orEmpty(),
            sentinelEndpoint = sentinel.endpoint, sentinelDcrId = sentinel.dcrId, sentinelStream = sentinel.stream,
            sentinelTenantId = sentinel.tenantId, sentinelClientId = sentinel.clientId, sentinelClientSecret = sentinel.clientSecret,
            sentinelAssertionFile = sentinel.assertionFile, sentinelAuthority = sentinel.authority,
        )
    }

    /** Teams from identity provider groups (sign-in claims and SCIM). See docs/domains/provisioning.md. */
    data class Groups(val autoCreate: Boolean = false, val filter: String = "", val ownerSuffix: String = "")

    data class Database(val url: String = "", val user: String = "", val password: String = "", val pool: Int = 20) {
        val jdbcUrl: String? get() = url.takeIf { it.isNotBlank() }?.let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
    }

    data class Oidc(
        val issuer: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val scopes: String = "openid email profile",
        val audiences: String = "",
        val groupsClaim: String = "groups",
    )

    /** Environment settings of the internal agent. Blank means "not set": the admin UI then decides. */
    data class Llm(
        val provider: String = "",
        val model: String = "",
        val baseUrl: String = "",
        val effort: String = "",
        val enabled: String = "",
        val apiKey: String = "",
        val authStyle: String = "",
        val credential: String = "",
        val tokenUrl: String = "",
        val issuer: String = "",
        val grant: String = "",
        val clientAuth: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val assertionFile: String = "",
        val tokenFile: String = "",
        val scope: String = "",
        val tokenParams: String = "",
        val headers: String = "",
    ) {
        fun environment(): LlmEnvironment {
            fun v(s: String) = s.trim().ifEmpty { null }
            fun <E : Enum<E>> enum(name: String, value: String, values: Array<E>): E? = v(value)?.let { raw ->
                values.firstOrNull { it.name.equals(raw.replace('-', '_'), ignoreCase = true) }
                    ?: throw IllegalArgumentException("$name must be one of ${values.joinToString { it.name.lowercase() }}")
            }
            val params = v(tokenParams)?.let { parsePairs(it, "MAYDAY_LLM_TOKEN_PARAMS") }
            params?.keys?.forEach { require(it.lowercase() !in RESERVED_TOKEN_PARAMS) { "MAYDAY_LLM_TOKEN_PARAMS: '$it' is set by Mayday" } }
            return LlmEnvironment(
                provider = v(provider)?.let { LlmApi.of(it).id },
                model = v(model),
                baseUrl = v(baseUrl),
                effort = v(effort),
                enabled = v(enabled)?.toBooleanStrict(),
                apiKey = v(apiKey),
                authStyle = v(authStyle)?.let { AuthStyle.valueOf(it.uppercase().replace('-', '_')) },
                credential = enum("MAYDAY_LLM_CREDENTIAL", credential, CredentialMode.entries.toTypedArray()),
                tokenUrl = v(tokenUrl),
                issuer = v(issuer),
                grant = enum("MAYDAY_LLM_GRANT", grant, OAuthGrant.entries.toTypedArray()),
                clientAuth = enum("MAYDAY_LLM_CLIENT_AUTH", clientAuth, ClientAuth.entries.toTypedArray()),
                clientId = v(clientId),
                clientSecret = v(clientSecret),
                assertionFile = v(assertionFile),
                tokenFile = v(tokenFile),
                scope = v(scope),
                tokenParams = params,
                headers = v(headers)?.let { parsePairs(it, "MAYDAY_LLM_HEADERS") },
            )
        }
    }

    /** OpenTelemetry settings that Spring Boot has no property for. See docs/observability.md. */
    data class TelemetrySettings(
        val endpoint: String = "",
        val environment: String = "",
        val captureContent: Boolean = false,
        val sampleAgentRuns: Boolean = true,
    )

    val baseUrl: String get() = publicUrl.trimEnd('/')
    val journalDir: Path? get() = dataDir.takeIf { it.isNotBlank() && it != "none" }?.let { Path.of(it) }

    fun appSettings() = AppSettings(
        publicUrl = baseUrl,
        secretKey = secretKey,
        adminEmails = csv(adminEmails).map { it.lowercase() }.toSet(),
        llm = llm.environment(),
        agentConcurrency = agentConcurrency,
        groups = mayday.provisioning.GroupSyncSettings(groups.autoCreate, groups.filter, groups.ownerSuffix),
        audit = audit.settings(),
        auditKey = audit.key,
    )

    companion object {
        fun csv(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** "name=value,name2=value2" */
        fun parsePairs(s: String, variable: String): Map<String, String> = csv(s).associate {
            val i = it.indexOf('=')
            require(i > 0) { "$variable: '$it' must be name=value" }
            it.substring(0, i).trim() to it.substring(i + 1).trim()
        }
    }
}

@SpringBootApplication
@ConfigurationPropertiesScan
class MaydayApplication

fun main(args: Array<String>) {
    if (args.firstOrNull() == "token-proxy") return mayday.sidecar.TokenProxy.main(args.drop(1).toTypedArray())
    if (args.firstOrNull() == "audit-verify") kotlin.system.exitProcess(mayday.audit.AuditVerify.main(args.drop(1).toTypedArray()))
    runApplication<MaydayApplication>(*args)
}

/** Composition root: builds the ports from configuration and the application on top of them. */
@Configuration(proxyBeanMethods = false)
class Wiring {
    private val log = Logger.getLogger("mayday")

    @Bean
    fun telemetry(openTelemetry: OpenTelemetry, props: MaydayProperties): Telemetry = OtelTelemetry(openTelemetry, props.telemetry.captureContent)

    @Bean(destroyMethod = "")
    fun ports(props: MaydayProperties, telemetry: Telemetry): Ports {
        val credentials = Credentials(telemetry = telemetry)
        return (props.database.jdbcUrl?.let { url ->
            val pool = Pool.of(url, props.database.user.ifBlank { null }, props.database.password.ifBlank { null }, props.database.pool)
            Ports.postgres(PostgresStore(pool), PgEventBus(pool))
        } ?: Ports.memory(MemoryStore(props.journalDir))).with(
            telemetry = telemetry,
            credentials = credentials,
            llm = LlmProviders.withTelemetry(telemetry),
            dispatcher = WebhookDispatcher(telemetry = telemetry),
            audit = props.audit.sinkConfig().build(credentials),
            pager = props.paging.pager(),
        )
    }

    @Bean(destroyMethod = "stop")
    fun app(props: MaydayProperties, ports: Ports): App {
        if (props.secretKey.isEmpty()) log.warning("MAYDAY_SECRET_KEY is not set. Sessions and stored secrets use a development key.")
        return App(props.appSettings(), ports).also { runBlocking { it.start() } }
    }

    @Bean
    fun oidc(props: MaydayProperties): OidcClient? = props.oidc.issuer.takeIf { it.isNotBlank() }?.let {
        OidcClient(OidcSettings(it, props.oidc.clientId, props.oidc.clientSecret, props.oidc.scopes,
            "${props.baseUrl}/auth/callback", MaydayProperties.csv(props.oidc.audiences).toSet(), props.oidc.groupsClaim))
    }

    @Bean
    fun sessions(props: MaydayProperties, app: App) =
        Sessions(app.signer, Duration.ofHours(props.sessionHours), props.baseUrl.startsWith("https"))

    @Bean
    fun authenticator(props: MaydayProperties, app: App, oidc: OidcClient?, sessions: Sessions) =
        Authenticator(app.identity, oidc, sessions, props.devLogin || (oidc == null && props.demo), props.baseUrl, app.audit)

    @Bean
    fun startup(props: MaydayProperties, app: App, oidc: OidcClient?) = ApplicationRunner {
        if (props.demo) runBlocking { Demo.seed(app) }
        val storage = when {
            props.database.jdbcUrl != null -> "PostgreSQL"
            props.journalDir != null -> "journal in ${props.journalDir}"
            else -> "memory only"
        }
        log.info("Mayday $VERSION is ready ($storage). Open ${props.baseUrl}")
        if (oidc == null && !props.devLogin && !props.demo) log.warning("No sign-in method is configured. Set MAYDAY_OIDC_ISSUER or MAYDAY_DEV_LOGIN=true.")
    }

    /** Marks agent runs that stopped reporting as failed. */
    @Bean
    fun reaper(app: App) = object : DisposableBean {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            scope.launch {
                while (isActive) {
                    delay(Duration.ofMinutes(5).toMillis())
                    runCatching { app.agents.reapStale(Duration.ofMinutes(30)) }
                }
            }
        }

        override fun destroy() = scope.cancel()
    }

    /** Pages the next level of escalations that nobody acknowledged in time. */
    @Bean
    fun escalationTicker(app: App) = object : DisposableBean {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            scope.launch {
                while (isActive) {
                    delay(Duration.ofSeconds(15).toMillis())
                    runCatching { while (app.escalation.tick() >= 500) Unit }
                }
            }
        }

        override fun destroy() = scope.cancel()
    }
}
