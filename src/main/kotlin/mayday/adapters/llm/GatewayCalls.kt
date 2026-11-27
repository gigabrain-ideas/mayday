package mayday.adapters.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import mayday.agents.statedCooldown
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mayday.agents.BudgetExhausted
import mayday.agents.GatewayAuth
import mayday.agents.GatewayError
import mayday.shared.Telemetry
import java.net.SocketTimeoutException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

private val RESTRICTED = setOf("connection", "content-length", "expect", "host", "upgrade")

/** One HTTP client for every gateway call. A 10-minute request timeout leaves room for long model turns. */
internal val gatewayHttp: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

/** POSTs a JSON body with the gateway headers. Returns the body of a 2xx answer, else throws [HttpFailure]. */
internal suspend fun postJson(client: HttpClient, uri: URI, body: String, headers: Map<String, String>): String {
    val b = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofMinutes(10))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.forEach { (k, v) -> if (k.lowercase() !in RESTRICTED) b.setHeader(k, v) }
    val r = try {
        client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).await()
    } catch (e: IOException) {
        throw ioFailure(e)
    }
    if (r.statusCode() !in 200..299) throw HttpFailure(r.statusCode(), statedCooldown(r.headers()::allValues, r.body()), errorDetail(r.body()))
    return r.body()
}

internal fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

/** Bounded retries for connection errors and HTTP 502/503/504. HTTP 429 and other statuses are never retried. */
data class RetryPolicy(
    val maxRetries: Int = 2,
    val baseDelay: Duration = Duration.ofMillis(500),
    val maxDelay: Duration = Duration.ofSeconds(8),
) {
    companion object {
        val DEFAULT = RetryPolicy()
    }
}

/** One failed attempt, in protocol-neutral form. [status] 0 = no HTTP answer. */
internal class HttpFailure(
    val status: Int,
    val retryAfter: Duration?,
    val detail: String,
    val retryable: Boolean = status in 502..504,
) : Exception(detail, null, false, false)

internal fun ioFailure(e: Throwable): HttpFailure {
    val timeout = (e is HttpTimeoutException && e !is HttpConnectTimeoutException) || e is SocketTimeoutException
    return HttpFailure(0, null, if (timeout) "request timed out" else "connection failed (${e.javaClass.simpleName})", retryable = !timeout)
}

/** Extracts `error.message` (Anthropic and OpenAI error shape) or a short prefix of the body. */
internal fun errorDetail(body: String): String {
    val parsed = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val error = parsed?.get("error")
    val message = ((error as? JsonObject)?.get("message") ?: error ?: parsed?.get("message")) as? JsonPrimitive
    return (message?.content ?: body).take(300)
}

/**
 * Runs one LLM call under the gateway contract: fresh identity and trace headers per attempt,
 * one token refresh on 401, [BudgetExhausted] on 429 without retry, bounded jittered retries on 502/503/504 and connection errors.
 */
internal class GatewayCaller(private val auth: GatewayAuth, private val telemetry: Telemetry, private val retry: RetryPolicy) {
    suspend fun <T> call(attempt: suspend (headers: Map<String, String>) -> T): T {
        var refreshed = false
        var retries = 0
        while (true) {
            val identity = auth.headers()
            val headers = LinkedHashMap(telemetry.propagationHeaders()).apply { putAll(identity) }
            val failure = try {
                return attempt(headers)
            } catch (f: HttpFailure) {
                f
            }
            val wait = failure.retryAfter
            when {
                failure.status == 401 && !refreshed -> {
                    auth.invalidate()
                    refreshed = true
                }
                failure.status == 429 -> throw BudgetExhausted(failure.retryAfter)
                failure.retryable && retries < retry.maxRetries && (wait == null || wait <= retry.maxDelay) -> {
                    retries++
                    delay((wait ?: backoff(retries)).toMillis())
                }
                else -> throw GatewayError(failure.status, scrub(describe(failure), identity))
            }
        }
    }

    private fun backoff(attempt: Int): Duration {
        val ceiling = minOf(retry.baseDelay.toMillis() shl (attempt - 1), retry.maxDelay.toMillis()).coerceAtLeast(1)
        return Duration.ofMillis(ceiling / 2 + ThreadLocalRandom.current().nextLong(ceiling / 2 + 1))
    }

    private fun describe(f: HttpFailure) =
        if (f.status == 0) "AI gateway call failed: ${f.detail}" else "AI gateway answered HTTP ${f.status}: ${f.detail}"

    private fun scrub(message: String, identity: Map<String, String>): String {
        var out = message
        identity.values.flatMap { listOf(it, it.removePrefix("Bearer ")) }.filter { it.length >= 8 }.forEach { out = out.replace(it, "***") }
        return out
    }
}
