package mayday.agents

import kotlinx.serialization.Serializable
import mayday.shared.DomainException
import java.time.Duration

/** Where the agent's API protocol comes from. */
@Serializable
enum class LlmApi(val id: String) {
    ANTHROPIC("anthropic"), OPENAI("openai");

    companion object {
        fun of(id: String): LlmApi = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
            ?: throw mayday.shared.Invalid("Provider must be anthropic or openai")
    }
}

/** How the credential goes on the wire. API_KEY = `x-api-key` (Anthropic style), BEARER = `Authorization: Bearer` (OpenAI style). */
@Serializable
enum class AuthStyle { API_KEY, BEARER }

/**
 * Source of the credential.
 * NONE: no auth header (for example mTLS or a network-level gateway).
 * STATIC: a fixed API key.
 * OAUTH2: a short-lived token from any OAuth 2.0 token endpoint, see [OAuthGrant] and [ClientAuth]. With an assertion
 *   file it is workload identity federation: the platform-issued token (for example a projected Kubernetes service
 *   account token) is the client assertion or the subject token.
 * FILE: a token that another process (for example a sidecar or Vault agent) writes to a file and refreshes.
 */
@Serializable
enum class CredentialMode { NONE, STATIC, OAUTH2, FILE }

/**
 * OAuth 2.0 grant of [CredentialMode.OAUTH2].
 * CLIENT_CREDENTIALS (RFC 6749 4.4): client secret, or the assertion file as JWT client assertion (RFC 7523 2.2).
 * TOKEN_EXCHANGE (RFC 8693): the assertion file is the subject token; client credentials are optional.
 */
@Serializable
enum class OAuthGrant { CLIENT_CREDENTIALS, TOKEN_EXCHANGE }

/** How the client secret goes to the token endpoint: BASIC = `client_secret_basic` (RFC 6749 2.3.1), POST = `client_secret_post`. */
@Serializable
enum class ClientAuth { BASIC, POST }

/** Form fields that Mayday sets itself. Extra token parameters cannot replace them. */
val RESERVED_TOKEN_PARAMS = setOf(
    "grant_type", "client_id", "client_secret", "client_assertion", "client_assertion_type", "subject_token", "scope",
)

/** Plain (unsealed) credential settings. Equal specs share one cached token. */
data class CredentialSpec(
    val mode: CredentialMode,
    val staticSecret: String = "",
    val tokenUrl: String = "",
    /** OIDC issuer. Without [tokenUrl], Mayday reads the token endpoint from the issuer metadata (OIDC Discovery, RFC 8414). */
    val issuer: String = "",
    val grant: OAuthGrant = OAuthGrant.CLIENT_CREDENTIALS,
    val clientAuth: ClientAuth = ClientAuth.BASIC,
    val clientId: String = "",
    val clientSecret: String = "",
    val assertionFile: String = "",
    val tokenFile: String = "",
    val scope: String = "",
    /** Extra form fields of the token request, for example `audience` or `resource` (RFC 8707). */
    val tokenParams: Map<String, String> = emptyMap(),
) {
    /** True when the settings are enough to get a token. */
    val complete: Boolean
        get() = when (mode) {
            CredentialMode.NONE -> true
            CredentialMode.STATIC -> staticSecret.isNotEmpty()
            CredentialMode.FILE -> tokenFile.isNotEmpty()
            CredentialMode.OAUTH2 -> (tokenUrl.isNotEmpty() || issuer.isNotEmpty()) && when (grant) {
                OAuthGrant.CLIENT_CREDENTIALS -> clientId.isNotEmpty() && (clientSecret.isNotEmpty() || assertionFile.isNotEmpty())
                OAuthGrant.TOKEN_EXCHANGE -> assertionFile.isNotEmpty()
            }
        }

    override fun toString() =
        "CredentialSpec(mode=$mode, grant=$grant, tokenUrl=$tokenUrl, issuer=$issuer, clientId=$clientId, scope=$scope, tokenParams=${tokenParams.keys})"
}

/** Supplies the credential for one call. Implementations cache short-lived tokens and refresh them before they expire. */
interface CredentialProvider {
    suspend fun token(): String

    /** Drops the cached token, for example after the gateway answered HTTP 401. */
    fun invalidate() = Unit
}

fun interface CredentialProviderFactory {
    /** Returns a shared provider per spec, so concurrent runs reuse one token. */
    fun create(spec: CredentialSpec): CredentialProvider
}

/**
 * Identity headers of the agent for the AI gateway and the tools gateway: the credential in the [style] header and
 * the custom [headers] (for example an agent registry id such as `x-company-agent-id`).
 */
class GatewayAuth(
    val style: AuthStyle,
    val credential: CredentialProvider?,
    val headers: Map<String, String> = emptyMap(),
) {
    suspend fun headers(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        headers.forEach { (k, v) -> if (k.isNotBlank()) out[k.trim()] = v }
        val token = credential?.token()
        if (!token.isNullOrEmpty()) {
            when (style) {
                AuthStyle.API_KEY -> out["x-api-key"] = token
                AuthStyle.BEARER -> out["Authorization"] = "Bearer $token"
            }
        }
        return out
    }

    fun invalidate() = credential?.invalidate() ?: Unit

    companion object {
        val none = GatewayAuth(AuthStyle.BEARER, null)
    }
}

/**
 * The gateway refused the call with HTTP 429: the agent's budget or rate limit is used up.
 * Callers must not retry. [retryAfter] comes from the Retry-After header when the gateway sends one.
 */
class BudgetExhausted(val retryAfter: Duration?, detail: String = "") : DomainException(
    "resource_exhausted",
    "The AI gateway refused the call (HTTP 429): the agent budget or rate limit is used up." +
        (retryAfter?.let { " Retry after ${it.seconds} s." } ?: "") + (if (detail.isNotBlank()) " $detail" else ""),
)

/** The gateway answered with an error that is not a budget refusal. [status] is the HTTP status, 0 for a network error. */
class GatewayError(val status: Int, message: String) : RuntimeException(message)

/** Cooldown after HTTP 429 when the response states none. */
val DEFAULT_COOLDOWN: Duration = Duration.ofMinutes(15)

/**
 * The cooldown that a 429 response states, or null. [header] returns all values of a header (case-insensitive name).
 * The first source that has a value wins:
 * 1. `Retry-After` (RFC 9110): delta seconds or an HTTP date.
 * 2. `retry-after-ms`: milliseconds.
 * 3. `RateLimit` (`t=<seconds>` parameter) and `RateLimit-Reset` (IETF RateLimit header fields).
 * 4. `X-RateLimit-Reset`: delta seconds, or epoch seconds when the value is a time stamp.
 * 5. The longest of the provider reset headers: `anthropic-ratelimit-*-reset` (RFC 3339 time) and
 *    `x-ratelimit-reset-requests` / `x-ratelimit-reset-tokens` (durations such as `6m0s`).
 * 6. A JSON body with a numeric `retry_after` (seconds), at the top level or in `error`.
 * Only header names and JSON structure are read, never message text.
 */
fun statedCooldown(header: (String) -> List<String>, body: String? = null, now: java.time.Instant = java.time.Instant.now()): Duration? {
    fun first(name: String) = header(name).firstOrNull { it.isNotBlank() }?.trim()
    first("Retry-After")?.let { v -> parseRetryAfter(v, now)?.let { return it } }
    first("retry-after-ms")?.toDoubleOrNull()?.let { return Duration.ofMillis(it.toLong().coerceAtLeast(0)) }
    first("RateLimit")?.let { v -> RATELIMIT_T.find(v)?.groupValues?.get(1)?.toLongOrNull()?.let { return Duration.ofSeconds(it) } }
    first("RateLimit-Reset")?.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
    first("X-RateLimit-Reset")?.toLongOrNull()?.let { v ->
        return if (v > EPOCH_THRESHOLD) Duration.between(now, java.time.Instant.ofEpochSecond(v)).coerceAtLeast(Duration.ZERO) else Duration.ofSeconds(v.coerceAtLeast(0))
    }
    val resets = buildList {
        listOf("requests", "tokens", "input-tokens", "output-tokens").forEach { kind ->
            first("anthropic-ratelimit-$kind-reset")?.let { v ->
                runCatching { java.time.OffsetDateTime.parse(v).toInstant() }.getOrNull()?.let { add(Duration.between(now, it).coerceAtLeast(Duration.ZERO)) }
            }
        }
        listOf("requests", "tokens").forEach { kind -> first("x-ratelimit-reset-$kind")?.let(::parseGoDuration)?.let(::add) }
    }
    resets.maxOrNull()?.let { return it }
    val json = body?.takeIf { it.isNotBlank() }?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
    val seconds = json?.let { o ->
        listOf(o, o["error"] as? kotlinx.serialization.json.JsonObject).firstNotNullOfOrNull { (it?.get("retry_after") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
    }
    return seconds?.let { Duration.ofMillis((it * 1000).toLong().coerceAtLeast(0)) }
}

private val RATELIMIT_T = Regex("""(?:^|;)\s*t\s*=\s*(\d+)""")
private val GO_DURATION_PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")
private const val EPOCH_THRESHOLD = 1_000_000_000L

/** Parses durations such as `1s`, `6m0s`, `1h2m3.5s` or `250ms`. */
internal fun parseGoDuration(v: String): Duration? {
    val s = v.trim()
    if (s.isEmpty() || GO_DURATION_PART.replace(s, "").isNotEmpty()) return null
    var millis = 0.0
    GO_DURATION_PART.findAll(s).forEach { m ->
        val n = m.groupValues[1].toDouble()
        millis += when (m.groupValues[2]) { "h" -> n * 3_600_000; "m" -> n * 60_000; "s" -> n * 1000; else -> n }
    }
    return Duration.ofMillis(millis.toLong())
}

/** Parses a Retry-After header value (delta seconds or an HTTP date). */
fun parseRetryAfter(value: String?, now: java.time.Instant = java.time.Instant.now()): Duration? {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return null
    v.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
    return runCatching {
        val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        Duration.between(now, at).takeIf { !it.isNegative } ?: Duration.ZERO
    }.getOrNull()
}

/** Environment settings of the internal agent. A non-null value overrides the stored setting and locks it in the UI. */
data class LlmEnvironment(
    val provider: String? = null,
    val model: String? = null,
    val baseUrl: String? = null,
    val effort: String? = null,
    val enabled: Boolean? = null,
    val apiKey: String? = null,
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
) {
    /** Names of the fields that the environment sets (LlmView JSON names). */
    val managedFields: List<String>
        get() = buildList {
            if (provider != null) add("provider")
            if (model != null) add("model")
            if (baseUrl != null) add("baseUrl")
            if (effort != null) add("effort")
            if (enabled != null) add("enabled")
            if (apiKey != null) add("apiKey")
            if (authStyle != null) add("authStyle")
            if (credential != null) add("credential")
            if (tokenUrl != null) add("tokenUrl")
            if (issuer != null) add("issuer")
            if (grant != null) add("grant")
            if (clientAuth != null) add("clientAuth")
            if (clientId != null) add("clientId")
            if (clientSecret != null) add("clientSecret")
            if (assertionFile != null) add("assertionFile")
            if (tokenFile != null) add("tokenFile")
            if (scope != null) add("scope")
            if (tokenParams != null) add("tokenParams")
            if (headers != null) add("headers")
        }
}

class StaticCredential(private val secret: String) : CredentialProvider {
    override suspend fun token() = secret
}
