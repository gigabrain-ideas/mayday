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
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.PgEventBus
import mayday.adapters.postgres.Pool
import mayday.adapters.postgres.PostgresStore
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
    val anthropicApiKey: String = "",
    val webDir: String = "",
    val sessionHours: Long = 12,
    val maxLiveConnections: Int = 100_000,
    val agentConcurrency: Int = 16,
) {
    data class Database(val url: String = "", val user: String = "", val password: String = "", val pool: Int = 20) {
        val jdbcUrl: String? get() = url.takeIf { it.isNotBlank() }?.let { if (it.startsWith("jdbc:")) it else "jdbc:$it" }
    }

    data class Oidc(
        val issuer: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val scopes: String = "openid email profile",
        val audiences: String = "",
    )

    val baseUrl: String get() = publicUrl.trimEnd('/')
    val journalDir: Path? get() = dataDir.takeIf { it.isNotBlank() && it != "none" }?.let { Path.of(it) }

    fun appSettings() = AppSettings(
        publicUrl = baseUrl,
        secretKey = secretKey,
        adminEmails = csv(adminEmails).map { it.lowercase() }.toSet(),
        anthropicApiKey = anthropicApiKey.ifBlank { null },
        agentConcurrency = agentConcurrency,
    )

    companion object {
        fun csv(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

@SpringBootApplication
@ConfigurationPropertiesScan
class MaydayApplication

fun main(args: Array<String>) {
    runApplication<MaydayApplication>(*args)
}

/** Composition root: builds the ports from configuration and the application on top of them. */
@Configuration(proxyBeanMethods = false)
class Wiring {
    private val log = Logger.getLogger("mayday")

    @Bean(destroyMethod = "")
    fun ports(props: MaydayProperties): Ports = props.database.jdbcUrl?.let { url ->
        val pool = Pool.of(url, props.database.user.ifBlank { null }, props.database.password.ifBlank { null }, props.database.pool)
        Ports.postgres(PostgresStore(pool), PgEventBus(pool))
    } ?: Ports.memory(MemoryStore(props.journalDir))

    @Bean(destroyMethod = "stop")
    fun app(props: MaydayProperties, ports: Ports): App {
        if (props.secretKey.isEmpty()) log.warning("MAYDAY_SECRET_KEY is not set. Sessions and stored secrets use a development key.")
        return App(props.appSettings(), ports).also { runBlocking { it.start() } }
    }

    @Bean
    fun oidc(props: MaydayProperties): OidcClient? = props.oidc.issuer.takeIf { it.isNotBlank() }?.let {
        OidcClient(OidcSettings(it, props.oidc.clientId, props.oidc.clientSecret, props.oidc.scopes,
            "${props.baseUrl}/auth/callback", MaydayProperties.csv(props.oidc.audiences).toSet()))
    }

    @Bean
    fun sessions(props: MaydayProperties, app: App) =
        Sessions(app.signer, Duration.ofHours(props.sessionHours), props.baseUrl.startsWith("https"))

    @Bean
    fun authenticator(props: MaydayProperties, app: App, oidc: OidcClient?, sessions: Sessions) =
        Authenticator(app.identity, oidc, sessions, props.devLogin || (oidc == null && props.demo), props.baseUrl)

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
}
