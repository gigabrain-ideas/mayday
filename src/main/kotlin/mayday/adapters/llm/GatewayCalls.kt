package mayday.adapters.llm

import kotlinx.coroutines.delay
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
