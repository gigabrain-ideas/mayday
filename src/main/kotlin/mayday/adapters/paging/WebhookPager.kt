package mayday.adapters.paging

import kotlinx.coroutines.future.await
import mayday.escalation.PageNotice
import mayday.escalation.Pager
import mayday.shared.Crypto
import mayday.shared.JSON
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Sends each page as JSON to one endpoint (a chat bridge, an SMS gateway, a push service).
 * With a secret, X-Mayday-Signature = sha256=HMAC(secret, timestamp + "." + body), as for agent webhooks.
 */
class WebhookPager(
    url: String,
    private val secret: String = "",
    private val headers: Map<String, String> = emptyMap(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : Pager {
    private val uri = URI(url)

    override suspend fun page(notice: PageNotice) {
        val body = JSON.encodeToString(PageNotice.serializer(), notice)
        val ts = (System.currentTimeMillis() / 1000).toString()
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mayday-Pager/1")
            .header("X-Mayday-Event", "alert.paged")
            .header("X-Mayday-Timestamp", ts)
            .apply { if (secret.isNotEmpty()) header("X-Mayday-Signature", "sha256=" + Crypto.hmacHex(secret, "$ts.$body")) }
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).await()
        if (resp.statusCode() !in 200..299) throw IllegalStateException("Pager endpoint answered HTTP ${resp.statusCode()}")
    }
}
