package mayday.adapters.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import mayday.App
import mayday.adapters.auth.Authenticator
import mayday.adapters.auth.Sessions
import mayday.alerting.AlertSignal
import mayday.alerting.AlertStatus
import mayday.identity.Principal
import mayday.shared.DomainException
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.JSON
import mayday.shared.Severity
import mayday.shared.Unauthenticated
import mayday.shared.Unavailable
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

private inline fun <reified T> json(value: T, status: HttpStatus = HttpStatus.OK, cookie: String? = null): ResponseEntity<String> {
    val b = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
    cookie?.let { b.header(HttpHeaders.SET_COOKIE, it) }
    return b.body(JSON.encodeToString(serializer<T>(), value))
}

private fun redirect(location: String, cookie: String? = null): ResponseEntity<String> {
    val b = ResponseEntity.status(HttpStatus.FOUND).location(URI(location))
    cookie?.let { b.header(HttpHeaders.SET_COOKIE, it) }
    return b.build()
}

private fun safeRedirect(r: String?) = r?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"

fun errorBody(code: String, message: String) = JSON.encodeToString(JsonElement.serializer(), buildJsonObject { put("error", code); put("message", message) })

private inline fun <reified T> decode(body: String?): T {
    if (body.isNullOrBlank()) throw Invalid("A JSON body is required")
    return try { JSON.decodeFromString(serializer<T>(), body) } catch (e: DomainException) { throw e } catch (e: IllegalArgumentException) {
        throw Invalid("Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
    }
}

@Serializable private data class DevLogin(val email: String, val name: String = "")
@Serializable private data class ExchangeResult(val access_token: String, val issued_token_type: String, val token_type: String, val expires_in: Long)

/** Browser sign-in. These endpoints set or clear the session cookie, so they stay plain HTTP. */
@RestController
class AuthEndpoints(private val auth: Authenticator) {
    private val sessions get() = auth.sessions

    @GetMapping("/auth/login")
    suspend fun login(@RequestParam(required = false) redirect: String?): ResponseEntity<String> {
        val client = auth.oidc ?: throw Unavailable("Single sign-on is not configured")
        val state = Ids.secret(18); val nonce = Ids.secret(18); val verifier = Ids.secret(48)
        val url = withContext(Dispatchers.IO) { client.authorizeUrl(state, nonce, verifier) }
        return redirect(url, sessions.cookie(Sessions.FLOW_COOKIE, sessions.flow(state, nonce, verifier, safeRedirect(redirect)), Duration.ofMinutes(10)))
    }

    @GetMapping("/auth/callback")
    suspend fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        @RequestHeader(HttpHeaders.COOKIE, required = false) cookies: String?,
    ): ResponseEntity<String> {
        val client = auth.oidc ?: throw Unavailable("Single sign-on is not configured")
        val flow = sessions.readFlow(Authenticator.cookie(cookies, Sessions.FLOW_COOKIE)) ?: throw Unauthenticated("The login took too long. Try again.")
        error?.let { throw Unauthenticated("The identity provider refused the login: $it") }
        if (state != flow.state) throw Unauthenticated("Invalid login state")
        val id = withContext(Dispatchers.IO) { client.exchange(code ?: throw Invalid("Missing code"), flow.verifier, flow.nonce) }
        val user = auth.ssoUser(id.subject, id.email, id.name)
        return redirect(flow.redirect, sessions.sessionCookie(user.id))
    }

    @PostMapping("/auth/dev-login")
    suspend fun devLogin(@RequestBody(required = false) body: String?): ResponseEntity<String> {
        val b = decode<DevLogin>(body)
        val user = auth.devUser(b.email, b.name)
        return json(user, cookie = sessions.sessionCookie(user.id))
    }

    @GetMapping("/auth/dev-login")
    suspend fun devLoginLink(@RequestParam(required = false) email: String?, @RequestParam(required = false) redirect: String?): ResponseEntity<String> {
        val user = auth.devUser(email ?: throw Invalid("email is required"), "")
        return redirect(safeRedirect(redirect), sessions.sessionCookie(user.id))
    }

    @PostMapping("/auth/logout")
    suspend fun logout(): ResponseEntity<String> {
        val url = auth.oidc?.let { withContext(Dispatchers.IO) { it.logoutUrl(auth.publicUrl) } } ?: "/"
        return json(mapOf("logoutUrl" to url), cookie = sessions.clear(Sessions.COOKIE))
    }

    /** RFC 8693 token exchange: an agent trades its own token and a user's token for a delegated token. */
    @PostMapping("/oauth/token", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    suspend fun token(exchange: ServerWebExchange): ResponseEntity<String> {
        val form = exchange.formData.awaitSingle().toSingleValueMap()
        if (form["grant_type"] != "urn:ietf:params:oauth:grant-type:token-exchange") throw Invalid("unsupported_grant_type")
        val subject = auth.bearer(form["subject_token"] ?: throw Invalid("subject_token is required"))
        val actor = auth.bearer(form["actor_token"] ?: throw Invalid("actor_token is required"))
        val ttl = Duration.ofHours(1)
        return json(ExchangeResult(auth.exchange(subject, actor, ttl), "urn:ietf:params:oauth:token-type:access_token", "Bearer", ttl.seconds))
    }
}

/** MCP server (Streamable HTTP, JSON responses) and alert intake for monitoring systems. */
@RestController
class IntegrationEndpoints(private val app: App, private val auth: Authenticator) {
    private suspend fun principal(authorization: String?, cookie: String?, requestedWith: String?): Principal =
        auth.principalForHttp(authorization, cookie, mutating = true, requestedWith = requestedWith)

    @PostMapping("/mcp")
    suspend fun mcp(
        @RequestBody(required = false) body: String?,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(HttpHeaders.COOKIE, required = false) cookie: String?,
        @RequestHeader("X-Requested-With", required = false) requestedWith: String?,
    ): ResponseEntity<String> {
        val p = principal(authorization, cookie, requestedWith)
        val msg = runCatching { JSON.parseToJsonElement(body.orEmpty()) }.getOrElse { throw Invalid("Parse error") }
        val out = app.mcp.handle(p, msg) ?: return ResponseEntity.accepted().build()
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.encodeToString(JsonElement.serializer(), out))
    }

    @GetMapping("/mcp")
    fun mcpStream(): ResponseEntity<String> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST")
        .contentType(MediaType.APPLICATION_JSON).body(errorBody("method_not_allowed", "This server does not open a server-to-client stream"))

    @PostMapping("/api/v1/alerts")
    suspend fun ingest(
        @RequestBody(required = false) body: String?,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(HttpHeaders.COOKIE, required = false) cookie: String?,
        @RequestHeader("X-Requested-With", required = false) requestedWith: String?,
    ): ResponseEntity<String> {
        val res = app.alerts.ingest(principal(authorization, cookie, requestedWith), decode<AlertSignal>(body))
        return json(res, if (res.created) HttpStatus.CREATED else HttpStatus.OK)
    }

    /** Prometheus Alertmanager webhook. The team comes from ?team= or the "team" label. */
    @PostMapping("/api/v1/integrations/alertmanager")
    suspend fun alertmanager(
        @RequestBody(required = false) body: String?,
        @RequestParam(required = false) team: String?,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestHeader(HttpHeaders.COOKIE, required = false) cookie: String?,
        @RequestHeader("X-Requested-With", required = false) requestedWith: String?,
    ): ResponseEntity<String> {
        val p = principal(authorization, cookie, requestedWith)
        val root = runCatching { JSON.parseToJsonElement(body.orEmpty()).jsonObject }.getOrElse { throw Invalid("Invalid JSON") }
        var created = 0; var updated = 0
        (root["alerts"] as? JsonArray).orEmpty().forEach { el ->
            val a = el.jsonObject
            val labels = (a["labels"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val ann = (a["annotations"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val res = app.alerts.ingest(p, AlertSignal(
                title = ann["summary"] ?: labels["alertname"] ?: "Alert",
                severity = severityOf(labels["severity"]),
                team = team ?: labels["team"] ?: throw Invalid("Set the 'team' label or the ?team= parameter"),
                source = "alertmanager",
                fingerprint = (a["fingerprint"] as? JsonPrimitive)?.content,
                status = if ((a["status"] as? JsonPrimitive)?.content == "resolved") AlertStatus.RESOLVED else AlertStatus.FIRING,
                description = ann["description"].orEmpty(),
                labels = labels,
                externalUrl = (a["generatorURL"] as? JsonPrimitive)?.content,
            ))
            if (res.created) created++ else updated++
        }
        return json(mapOf("created" to created, "updated" to updated))
    }

    private fun severityOf(s: String?): Severity = when (s?.lowercase()) {
        "critical", "page", "p1", "sev1", "fatal" -> Severity.SEV1
        "high", "error", "major", "p2", "sev2" -> Severity.SEV2
        "warning", "warn", "medium", "p3", "sev3" -> Severity.SEV3
        "low", "minor", "p4", "sev4" -> Severity.SEV4
        "info", "informational", "p5", "sev5" -> Severity.SEV5
        else -> Severity.SEV3
    }
}

/** Every HTTP error has the body {"error": code, "message": text}. */
@RestControllerAdvice
class HttpErrors {
    private val log = Logger.getLogger("mayday.http")

    @ExceptionHandler(DomainException::class)
    fun domain(e: DomainException): ResponseEntity<String> = ResponseEntity.status(statusOf(e.code))
        .contentType(MediaType.APPLICATION_JSON).body(errorBody(e.code, e.message ?: e.code))

    @ExceptionHandler(org.springframework.web.server.ServerWebInputException::class)
    fun input(e: org.springframework.web.server.ServerWebInputException): ResponseEntity<String> = ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON).body(errorBody("invalid", e.reason ?: "Invalid request"))

    @ExceptionHandler(org.springframework.web.server.UnsupportedMediaTypeStatusException::class)
    fun mediaType(e: org.springframework.web.server.UnsupportedMediaTypeStatusException): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).contentType(MediaType.APPLICATION_JSON).body(errorBody("invalid", "Unsupported content type"))

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun status(e: org.springframework.web.server.ResponseStatusException): ResponseEntity<String> {
        val code = when (e.statusCode.value()) {
            404 -> "not_found"
            405 -> "method_not_allowed"
            401 -> "unauthenticated"
            403 -> "forbidden"
            503 -> "unavailable"
            in 400..499 -> "invalid"
            else -> "internal"
        }
        return ResponseEntity.status(e.statusCode).contentType(MediaType.APPLICATION_JSON).body(errorBody(code, e.reason ?: code))
    }

    @ExceptionHandler(Exception::class)
    fun other(e: Exception): ResponseEntity<String> {
        log.log(Level.SEVERE, "Request failed", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
            .body(errorBody("internal", "Something went wrong. The error is logged."))
    }

    companion object {
        fun statusOf(code: String) = when (code) {
            "not_found" -> HttpStatus.NOT_FOUND
            "forbidden", "locked" -> HttpStatus.FORBIDDEN
            "conflict" -> HttpStatus.CONFLICT
            "unauthenticated" -> HttpStatus.UNAUTHORIZED
            "unavailable" -> HttpStatus.SERVICE_UNAVAILABLE
            else -> HttpStatus.BAD_REQUEST
        }
    }
}
