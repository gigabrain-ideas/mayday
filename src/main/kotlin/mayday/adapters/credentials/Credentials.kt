package mayday.adapters.credentials

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mayday.agents.CredentialMode
import mayday.agents.CredentialProvider
import mayday.agents.CredentialProviderFactory
import mayday.agents.CredentialSpec
import mayday.agents.GatewayError
import mayday.agents.OAuthGrant
import mayday.agents.RESERVED_TOKEN_PARAMS
import mayday.agents.StaticCredential
import mayday.shared.SpanKind
import mayday.shared.Telemetry
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** One shared provider per spec, so concurrent agent runs reuse one token. */
class Credentials(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val telemetry: Telemetry = Telemetry.noop,
    private val clock: () -> Instant = Instant::now,
) : CredentialProviderFactory {
    private val providers = ConcurrentHashMap<CredentialSpec, CredentialProvider>()

    override fun create(spec: CredentialSpec): CredentialProvider = providers.computeIfAbsent(spec) {
        when (it.mode) {
            CredentialMode.NONE -> StaticCredential("")
            CredentialMode.STATIC -> StaticCredential(it.staticSecret)
            CredentialMode.OAUTH2 -> OAuth2Credential(it, http, telemetry, clock)
            CredentialMode.FILE -> FileCredential(it, telemetry)
        }
    }
}

/**
 * Token from any standard OAuth 2.0 authorization server.
 * - Endpoint: [CredentialSpec.tokenUrl], or `token_endpoint` from the issuer metadata (OIDC Discovery, then RFC 8414).
 * - Grant: client credentials (secret, or the assertion file as JWT client assertion, RFC 7523) or token exchange
 *   (RFC 8693, the assertion file is the subject token).
 * - Client secret: HTTP Basic (default, RFC 6749 2.3.1) or form fields.
 * - Lifetime: `expires_in`, else the `exp` claim when the token is a JWT, else 5 minutes.
 * The assertion file is read again on every fetch because the platform rotates it.
 */
class OAuth2Credential(
    private val spec: CredentialSpec,
    private val http: HttpClient,
    private val telemetry: Telemetry,
    private val clock: () -> Instant,
) : CredentialProvider {
    private class Cached(val token: String, val expiresAt: Instant, val refreshAt: Instant)

    @Volatile private var cached: Cached? = null
    private val mutex = Mutex()
    @Volatile private var endpoint: URI? = spec.tokenUrl.takeIf { it.isNotEmpty() }?.let(::httpUri)

    override suspend fun token(): String {
        cached?.let { if (clock().isBefore(it.refreshAt)) return it.token }
        return mutex.withLock {
            val current = cached
            val now = clock()
            if (current != null && now.isBefore(current.refreshAt)) return@withLock current.token
            try {
                fetch().also { cached = it }.token
            } catch (e: GatewayError) {
                if (current == null || !now.isBefore(current.expiresAt)) throw e
                cached = Cached(current.token, current.expiresAt, minOf(current.expiresAt, now.plus(RETRY_AFTER_FAILURE)))
                current.token
            }
        }
    }

    override fun invalidate() {
        cached = null
    }

    private suspend fun fetch(): Cached = fetch(endpoint ?: discover().also { endpoint = it })

    /** Reads `token_endpoint` from the issuer metadata: OIDC Discovery first, RFC 8414 when that answers 404. */
    private suspend fun discover(): URI {
        if (spec.tokenUrl.isNotEmpty()) throw GatewayError(0, "The token URL '${spec.tokenUrl}' is not a valid http(s) URL")
        val issuer = httpUri(spec.issuer.trimEnd('/')) ?: throw GatewayError(0, "OAuth2 needs a token URL or an http(s) issuer")
        val path = issuer.rawPath.orEmpty()
        val origin = URI(issuer.scheme, null, issuer.host, issuer.port, null, null, null).toString()
        val candidates = listOf("$issuer/.well-known/openid-configuration", "$origin/.well-known/oauth-authorization-server$path")
        return telemetry.span("credential.discover", mapOf("server.address" to issuer.host), SpanKind.CLIENT) { span ->
            var last = 0
            for (candidate in candidates) {
                val response = try {
                    http.sendAsync(
                        HttpRequest.newBuilder(URI(candidate)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString(),
                    ).await()
                } catch (e: IOException) {
                    span.fail("issuer not reachable")
                    throw GatewayError(0, "The issuer ${issuer.host} is not reachable (${e.javaClass.simpleName})")
                }
                last = response.statusCode()
                if (last == 404) continue
                val meta = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
                if (last !in 200..299 || meta == null) break
                if (meta.str("issuer")?.trimEnd('/') != spec.issuer.trimEnd('/')) {
                    span.fail("issuer mismatch")
                    throw GatewayError(last, "The metadata of ${issuer.host} names another issuer")
                }
                val token = meta.str("token_endpoint")?.let(::httpUri)
                    ?: throw GatewayError(last, "The metadata of ${issuer.host} has no http(s) token_endpoint")
                span.set("mayday.credential.token_endpoint", token.toString())
                return@span token
            }
            span.fail("no metadata")
            throw GatewayError(last, "The issuer ${issuer.host} has no OAuth 2.0 metadata (HTTP $last)")
        }
    }

    private suspend fun fetch(uri: URI): Cached = telemetry.span(
        "credential.fetch",
        mapOf(
            "mayday.credential.mode" to "oauth2",
            "mayday.credential.grant" to spec.grant.name.lowercase(),
            "mayday.credential.client_auth" to clientAuthentication(),
            "server.address" to uri.host,
            "server.port" to (if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443),
        ),
        SpanKind.CLIENT,
    ) { span ->
        try {
            val response = try {
                http.sendAsync(request(uri), HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: IOException) {
                throw GatewayError(0, "The token endpoint ${uri.host} is not reachable (${e.javaClass.simpleName})")
            }
            span.set("http.response.status_code", response.statusCode())
            val body = runCatching { Json.parseToJsonElement(response.body()).jsonObject }.getOrNull()
            if (response.statusCode() !in 200..299) {
                val reason = body?.let { listOfNotNull(it.str("error"), it.str("error_description")).joinToString(": ") }
                    ?.takeIf { it.isNotBlank() } ?: "no OAuth error in the response"
                throw GatewayError(response.statusCode(), "The token endpoint ${uri.host} answered HTTP ${response.statusCode()}: ${reason.take(500)}")
            }
            val token = body?.str("access_token")?.takeIf { it.isNotEmpty() }
                ?: throw GatewayError(response.statusCode(), "The token endpoint ${uri.host} answered without access_token")
            val now = clock()
            val expiresAt = body.str("expires_in")?.toDoubleOrNull()?.let { now.plusSeconds(it.toLong()) }
                ?: jwtExpiry(token)
                ?: now.plus(DEFAULT_LIFETIME)
            val lifetime = Duration.between(now, expiresAt).coerceAtLeast(Duration.ZERO)
            span.set("mayday.credential.expires_in", lifetime.seconds)
            Cached(token, expiresAt, expiresAt.minus(refreshMargin(lifetime)))
        } catch (e: GatewayError) {
            span.fail(e.message ?: "token fetch failed")
            throw e
        }
    }

    /** How the client authenticates: basic, post, assertion or none (public client). */
    private fun clientAuthentication(): String = when {
        spec.clientSecret.isNotEmpty() -> spec.clientAuth.name.lowercase()
        spec.grant == OAuthGrant.CLIENT_CREDENTIALS && spec.assertionFile.isNotEmpty() -> "assertion"
        else -> "none"
    }

    private suspend fun request(uri: URI): HttpRequest {
        val form = LinkedHashMap<String, String>()
        spec.tokenParams.forEach { (k, v) -> if (k.lowercase() !in RESERVED_TOKEN_PARAMS) form[k] = v }
        when (spec.grant) {
            OAuthGrant.CLIENT_CREDENTIALS -> form["grant_type"] = "client_credentials"
            OAuthGrant.TOKEN_EXCHANGE -> {
                if (spec.assertionFile.isEmpty()) throw GatewayError(0, "Token exchange needs an assertion file with the subject token")
                form["grant_type"] = "urn:ietf:params:oauth:grant-type:token-exchange"
                form["subject_token"] = readFile(spec.assertionFile, "assertion file")
                form.putIfAbsent("subject_token_type", "urn:ietf:params:oauth:token-type:jwt")
            }
        }
        if (spec.scope.isNotEmpty()) form["scope"] = spec.scope
        val b = HttpRequest.newBuilder(uri)
        when (clientAuthentication()) {
            "basic" -> b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${enc(spec.clientId)}:${enc(spec.clientSecret)}".toByteArray()))
            "post" -> { form["client_id"] = spec.clientId; form["client_secret"] = spec.clientSecret }
            "assertion" -> {
                form["client_id"] = spec.clientId
                form["client_assertion_type"] = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                form["client_assertion"] = readFile(spec.assertionFile, "assertion file")
            }
            else -> {
                if (spec.grant == OAuthGrant.CLIENT_CREDENTIALS) throw GatewayError(0, "The client credentials grant needs a client secret or an assertion file")
                if (spec.clientId.isNotEmpty()) form["client_id"] = spec.clientId
            }
        }
        val body = form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return b.timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    companion object {
        private val DEFAULT_LIFETIME: Duration = Duration.ofMinutes(5)
        private val RETRY_AFTER_FAILURE: Duration = Duration.ofSeconds(5)

        /** max(30 s, min(5 min, 20 % of the lifetime)), but never more than half of the lifetime. */
        fun refreshMargin(lifetime: Duration): Duration =
            maxOf(Duration.ofSeconds(30), minOf(Duration.ofMinutes(5), lifetime.dividedBy(5))).coerceAtMost(lifetime.dividedBy(2))

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        private fun httpUri(s: String): URI? = runCatching { URI(s) }.getOrNull()?.takeIf { it.host != null && it.scheme in setOf("http", "https") }

        /** The `exp` claim of a JWT access token. The signature is not checked: the value only schedules the refresh. */
        internal fun jwtExpiry(token: String): Instant? {
            val parts = token.split('.')
            if (parts.size != 3) return null
            val claims = runCatching { Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')))).jsonObject }.getOrNull()
            return claims?.str("exp")?.toDoubleOrNull()?.let { Instant.ofEpochSecond(it.toLong()) }
        }
        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
    }
}

/** A token that another process writes to a file. Read again when the modification time changes or after [invalidate]. */
class FileCredential(private val spec: CredentialSpec, private val telemetry: Telemetry) : CredentialProvider {
    private class Cached(val token: String, val modified: FileTime)

    @Volatile private var cached: Cached? = null
    private val path: Path = Path.of(spec.tokenFile)

    override suspend fun token(): String = withContext(Dispatchers.IO) {
        val modified = try {
            Files.getLastModifiedTime(path)
        } catch (_: IOException) {
            throw GatewayError(0, "The token file ${spec.tokenFile} does not exist or is not readable")
        }
        cached?.takeIf { it.modified == modified }?.let { return@withContext it.token }
        telemetry.span("credential.fetch", mapOf("mayday.credential.mode" to "file"), SpanKind.INTERNAL) { span ->
            val token = runCatching { readFile(spec.tokenFile, "token file") }.onFailure { span.fail(it.message ?: "read failed") }.getOrThrow()
            cached = Cached(token, modified)
            token
        }
    }

    override fun invalidate() {
        cached = null
    }
}

private suspend fun readFile(file: String, what: String): String = withContext(Dispatchers.IO) {
    val content = try {
        Files.readString(Path.of(file)).trim()
    } catch (_: NoSuchFileException) {
        throw GatewayError(0, "The $what $file does not exist")
    } catch (e: IOException) {
        throw GatewayError(0, "The $what $file is not readable (${e.javaClass.simpleName})")
    }
    content.ifEmpty { throw GatewayError(0, "The $what $file is empty") }
}
