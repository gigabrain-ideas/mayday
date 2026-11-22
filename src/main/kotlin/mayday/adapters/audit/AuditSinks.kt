package mayday.adapters.audit

import kotlinx.coroutines.runBlocking
import mayday.agents.CredentialProvider
import mayday.audit.AuditBatch
import mayday.audit.AuditSink
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Base64

private val defaultHttp: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

/** One JSON line per record on standard output. Collectors (Grafana Alloy, Promtail, Fluent Bit, Azure Monitor Agent) pick it up. */
class StdoutSink(private val out: PrintStream = System.out) : AuditSink {
    override val name = "stdout"

    override fun write(batch: AuditBatch) {
        val text = buildString(batch.lines.sumOf { it.length + 1 }) { batch.lines.forEach { append(it).append('\n') } }
        synchronized(out) {
            out.print(text)
            out.flush()
        }
        if (out.checkError()) throw IOException("standard output is closed")
    }
}

/**
 * Appends JSON lines to a file and forces them to disk. The file is opened for each batch, so log rotation
 * (logrotate with or without copytruncate) needs no signal.
 */
class FileSink(private val path: Path) : AuditSink {
    override val name = "file"

    override fun write(batch: AuditBatch) {
        path.parent?.let { Files.createDirectories(it) }
        val bytes = buildString { batch.lines.forEach { append(it).append('\n') } }.toByteArray()
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { ch ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) ch.write(buf)
            ch.force(false)
        }
    }
}

private fun send(http: HttpClient, request: HttpRequest, what: String) {
    val r = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (r.statusCode() !in 200..299) throw IOException("$what answered HTTP ${r.statusCode()}: ${r.body().take(300)}")
}

/** Splits lines into chunks whose encoded size stays below [maxBytes]. */
internal fun chunks(lines: List<String>, maxBytes: Int): List<List<String>> {
    val out = ArrayList<List<String>>()
    var cur = ArrayList<String>()
    var size = 0
    for (l in lines) {
        val n = l.toByteArray().size + 2
        if (cur.isNotEmpty() && size + n > maxBytes) { out += cur; cur = ArrayList(); size = 0 }
        cur += l; size += n
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

/**
 * Any HTTP collector: Splunk HEC (raw endpoint), Elastic, Datadog, Fluent Bit or Vector HTTP input, a custom webhook.
 * [ndjson] true sends one record per line, else a JSON array.
 */
class HttpSink(
    private val url: URI,
    private val headers: Map<String, String> = emptyMap(),
    private val ndjson: Boolean = false,
    private val http: HttpClient = defaultHttp,
    private val maxBytes: Int = 1_000_000,
) : AuditSink {
    override val name = "http"

    override fun write(batch: AuditBatch) {
        for (part in chunks(batch.lines, maxBytes)) {
            val body = if (ndjson) part.joinToString("\n", postfix = "\n") else part.joinToString(",", "[", "]")
            val b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .header("Content-Type", if (ndjson) "application/x-ndjson" else "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
            headers.forEach { (k, v) -> b.header(k, v) }
            send(http, b.build(), "The audit collector")
        }
    }
}

/**
 * Grafana Loki push API (`/loki/api/v1/push`). Labels have low cardinality: app, stream, category and outcome.
 * Actor, target and the other fields stay in the JSON line; query them with `| json`.
 */
class LokiSink(
    url: URI,
    private val tenant: String = "",
    private val headers: Map<String, String> = emptyMap(),
    private val labels: Map<String, String> = emptyMap(),
    private val http: HttpClient = defaultHttp,
    private val maxBytes: Int = 1_000_000,
) : AuditSink {
    override val name = "loki"
    private val push: URI = if (url.path.isNullOrEmpty() || url.path == "/") url.resolve("/loki/api/v1/push") else url

    override fun write(batch: AuditBatch) {
        val groups = batch.records.indices.groupBy { batch.records[it].category to batch.records[it].outcome }
        for ((key, idx) in groups) {
            val l = linkedMapOf("app" to "mayday", "stream" to "audit") + labels +
                mapOf("category" to key.first.name.lowercase(), "outcome" to key.second.name.lowercase())
            val labelJson = l.entries.joinToString(",", "{", "}") { (k, v) -> "${q(k)}:${q(v)}" }
            val values = idx.map { "[${q(nanos(batch.records[it].time))},${q(batch.lines[it])}]" }
            for (chunk in chunks(values, maxBytes)) {
                val body = """{"streams":[{"stream":$labelJson,"values":[${chunk.joinToString(",")}]}]}"""
                val b = HttpRequest.newBuilder(push).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                if (tenant.isNotEmpty()) b.header("X-Scope-OrgID", tenant)
                headers.forEach { (k, v) -> b.header(k, v) }
                send(http, b.build(), "Loki")
            }
        }
    }

    private fun nanos(t: java.time.Instant) = (t.epochSecond * 1_000_000_000L + t.nano).toString()

    companion object {
        fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    }
}

/**
 * Microsoft Sentinel through the Azure Monitor Logs Ingestion API (data collection endpoint + data collection rule).
 * Each record gets `TimeGenerated`. The token comes from Microsoft Entra ID (client secret or workload identity).
 */
class SentinelSink(
    endpoint: URI,
    dcrId: String,
    stream: String,
    private val credential: CredentialProvider,
    private val http: HttpClient = defaultHttp,
    private val maxBytes: Int = 1_000_000,
) : AuditSink {
    override val name = "sentinel"
    private val url: URI = URI("${endpoint.toString().trimEnd('/')}/dataCollectionRules/$dcrId/streams/$stream?api-version=2023-01-01")

    override fun write(batch: AuditBatch) {
        val lines = batch.records.indices.map { i -> """{"TimeGenerated":${q(batch.records[i].time.toString())},""" + batch.lines[i].substring(1) }
        for (part in chunks(lines, maxBytes)) {
            val token = runBlocking { credential.token() }
            val r = http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(part.joinToString(",", "[", "]"))).build(), HttpResponse.BodyHandlers.ofString())
            if (r.statusCode() == 401 || r.statusCode() == 403) credential.invalidate()
            if (r.statusCode() !in 200..299) throw IOException("Azure Monitor answered HTTP ${r.statusCode()}: ${r.body().take(300)}")
        }
    }
}

private fun q(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}
