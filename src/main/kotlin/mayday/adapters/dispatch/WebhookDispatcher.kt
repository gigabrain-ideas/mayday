package mayday.adapters.dispatch

import mayday.agents.AgentConfig
import mayday.agents.AgentDispatcher
import mayday.agents.DispatchPayload
import mayday.shared.Crypto
import mayday.shared.JSON
import mayday.shared.SpanKind
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Push delivery. The body is signed: X-Mayday-Signature = sha256=HMAC(secret, timestamp + "." + body).
 * Receivers must check the timestamp to block replays. The W3C traceparent header links the receiver's trace to the run.
 */
class WebhookDispatcher(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val telemetry: Telemetry = Telemetry.noop,
) : AgentDispatcher {
    override suspend fun dispatch(agent: AgentConfig, secret: String, payload: DispatchPayload) {
        val url = agent.endpointUrl ?: throw IllegalStateException("The agent has no endpoint URL")
        val uri = URI(url)
        val attrs = mapOf(
            "http.request.method" to "POST",
            "server.address" to uri.host,
            "server.port" to uri.port.takeIf { it > 0 },
            "mayday.agent.id" to agent.id,
            "mayday.run.id" to payload.run.id,
        )
        telemetry.span("POST webhook", attrs, SpanKind.CLIENT) { span -> send(uri, secret, payload, span) }
    }

    private suspend fun send(uri: URI, secret: String, payload: DispatchPayload, span: SpanScope) {
        val trace = telemetry.propagationHeaders()
        val body = JSON.encodeToString(DispatchPayload.serializer(), payload)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sig = Crypto.hmacHex(secret, "$ts.$body")
        var last: Exception? = null
        for (attempt in 0 until 3) {
            span.set("http.request.resend_count", attempt.takeIf { it > 0 })
            try {
                val req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mayday-Webhook/1")
                    .header("X-Mayday-Event", payload.event)
                    .header("X-Mayday-Delivery", payload.run.id)
                    .header("X-Mayday-Timestamp", ts)
                    .header("X-Mayday-Signature", "sha256=$sig")
                    .apply { trace.forEach { (k, v) -> header(k, v) } }
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val resp = http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await()
                span.set("http.response.status_code", resp.statusCode())
                if (resp.statusCode() in 200..299) return
                if (resp.statusCode() in 400..499 && resp.statusCode() != 429) throw IllegalStateException("Endpoint answered HTTP ${resp.statusCode()}")
                last = IllegalStateException("Endpoint answered HTTP ${resp.statusCode()}")
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
            delay(500L shl attempt)
        }
        throw last ?: IllegalStateException("Delivery failed")
    }
}
