package mayday.adapters.dispatch

import mayday.agents.AgentConfig
import mayday.agents.AgentDispatcher
import mayday.agents.DispatchPayload
import mayday.shared.Crypto
import mayday.shared.JSON
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Push delivery. The body is signed: X-Mayday-Signature = sha256=HMAC(secret, timestamp + "." + body).
 * Receivers must check the timestamp to block replays.
 */
class WebhookDispatcher(
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : AgentDispatcher {
    override fun dispatch(agent: AgentConfig, secret: String, payload: DispatchPayload) {
        val url = agent.endpointUrl ?: throw IllegalStateException("The agent has no endpoint URL")
        val body = JSON.encodeToString(DispatchPayload.serializer(), payload)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sig = Crypto.hmacHex(secret, "$ts.$body")
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val req = HttpRequest.newBuilder(URI(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mayday-Webhook/1")
                    .header("X-Mayday-Event", payload.event)
                    .header("X-Mayday-Delivery", payload.run.id)
                    .header("X-Mayday-Timestamp", ts)
                    .header("X-Mayday-Signature", "sha256=$sig")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
                val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                if (resp.statusCode() in 200..299) return
                if (resp.statusCode() in 400..499 && resp.statusCode() != 429) throw IllegalStateException("Endpoint answered HTTP ${resp.statusCode()}")
                last = IllegalStateException("Endpoint answered HTTP ${resp.statusCode()}")
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
            Thread.sleep(500L shl attempt)
        }
        throw last ?: IllegalStateException("Delivery failed")
    }
}
