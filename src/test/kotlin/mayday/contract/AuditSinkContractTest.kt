package mayday.contract

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.RecordingSink
import mayday.adapters.audit.FileSink
import mayday.adapters.audit.HttpSink
import mayday.adapters.audit.LokiSink
import mayday.adapters.audit.SentinelSink
import mayday.adapters.audit.StdoutSink
import mayday.agents.CredentialProvider
import mayday.audit.AuditActor
import mayday.audit.AuditBatch
import mayday.audit.AuditCategory
import mayday.audit.AuditChain
import mayday.audit.AuditEntry
import mayday.audit.AuditLog
import mayday.audit.AuditOutcome
import mayday.audit.AuditParty
import mayday.audit.AuditSettings
import mayday.audit.AuditSink
import mayday.shared.JSON
import org.junit.jupiter.api.AfterEach
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every audit sink adapter passes this contract: the destination receives every record of a batch unchanged and in order,
 * so the hash chain verifies there; a refused delivery throws, so the audit log retries it.
 */
abstract class AuditSinkContract {
    protected val key = AuditChain.key("sink-contract")

    /** The sink under test that delivers to a working destination. */
    protected abstract fun sink(): AuditSink

    /** The JSON lines the destination holds, in arrival order. */
    protected abstract fun received(): List<String>

    /** A sink whose destination refuses or cannot take the batch. */
    protected abstract fun broken(): AuditSink

    protected fun batch(n: Int, quote: String = "plain"): AuditBatch {
        val rec = RecordingSink()
        val log = AuditLog(listOf(rec), AuditSettings(instance = "contract/1"), key)
        repeat(n) {
            log.emit(AuditEntry(AuditCategory.AUTHORIZATION, "grpc:IncidentService/DeclareIncident",
                AuditActor(AuditParty("agent", "a$it", "Agent \"$quote\""), AuditParty("user", "u1", "Ann", "ann@example.com"), runId = "r$it"),
                if (it % 2 == 0) AuditOutcome.SUCCESS else AuditOutcome.DENIED, details = mapOf("line" to "a\nb\t\\c")))
        }
        assertTrue(log.flush())
        log.close()
        val records = rec.records.toList()
        return AuditBatch(records, rec.lines.toList())
    }

    @Test
    fun `the destination receives every record unchanged and in order`() {
        val b = batch(25)
        sink().write(b)
        val got = received()
        assertEquals(b.size, got.size)
        val ids = got.map { JSON.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(b.records.map { it.id }, ids)
        val report = AuditChain.verify(got.asSequence(), key)
        assertTrue(report.ok, report.problems.toString())
    }

    @Test
    fun `special characters survive delivery`() {
        val b = batch(3, quote = "x\"y\\z</script>ü")
        sink().write(b)
        assertTrue(AuditChain.verify(received().asSequence(), key).ok)
    }

    @Test
    fun `a refused delivery throws so the log retries`() {
        assertFailsWith<Exception> { broken().write(batch(2)) }
    }
}

class StdoutSinkContractTest : AuditSinkContract() {
    private val out = ByteArrayOutputStream()
    override fun sink() = StdoutSink(PrintStream(out, true, Charsets.UTF_8))
    override fun received() = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    override fun broken() = StdoutSink(PrintStream(object : OutputStream() {
        override fun write(b: Int) = throw java.io.IOException("closed")
    }))
}

class FileSinkContractTest : AuditSinkContract() {
    private val dir: Path = Files.createTempDirectory("audit")
    private val file = dir.resolve("logs/audit.jsonl")
    override fun sink() = FileSink(file)
    override fun received() = Files.readAllLines(file).filter { it.isNotBlank() }
    override fun broken() = FileSink(dir)

    @Test
    fun `appends across batches and survives rotation`() {
        val s = sink()
        s.write(batch(2))
        Files.move(file, dir.resolve("logs/audit.jsonl.1"))
        val second = batch(3)
        s.write(second)
        assertEquals(second.records.map { it.id }, received().map { JSON.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content })
    }
}

/** A local HTTP server that plays the destination and records the requests. */
abstract class HttpDestinationContract : AuditSinkContract() {
    protected class Request(val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    protected val requests = CopyOnWriteArrayList<Request>()
    @Volatile protected var status = 204
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += Request(ex.requestURI.path, ex.requestURI.query, ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }, body)
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        start()
    }
    protected val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterEach
    fun stop() = server.stop(0)
}

class HttpSinkContractTest : HttpDestinationContract() {
    override fun sink() = HttpSink(URI("$base/ingest"), mapOf("Authorization" to "Splunk abc"))
    override fun received() = requests.flatMap { r -> JSON.parseToJsonElement(r.body).jsonArray.map { it.toString() } }
    override fun broken() = HttpSink(URI("$base/ingest")).also { status = 500 }

    @Test
    fun `sends the configured headers and NDJSON on request`() {
        HttpSink(URI("$base/ingest"), mapOf("Authorization" to "Splunk abc"), ndjson = true).write(batch(3))
        val r = requests.single()
        assertEquals("Splunk abc", r.headers["authorization"])
        assertEquals("application/x-ndjson", r.headers["content-type"])
        assertEquals(5, r.body.lines().count { it.isNotBlank() }, "3 entries plus audit.started and audit.stopped")
    }

    @Test
    fun `splits large batches below the size limit`() {
        HttpSink(URI("$base/ingest"), maxBytes = 2_000).write(batch(10))
        assertTrue(requests.size > 1)
        assertTrue(requests.all { it.body.toByteArray().size <= 2_000 })
        assertEquals(12, received().size, "10 entries plus audit.started and audit.stopped")
    }
}

class LokiSinkContractTest : HttpDestinationContract() {
    override fun sink() = LokiSink(URI(base), tenant = "acme", headers = mapOf("Authorization" to LokiSink.basic("u", "p")), labels = mapOf("env" to "prod"))
    override fun received(): List<String> {
        val values = requests.flatMap { r ->
            JSON.parseToJsonElement(r.body).jsonObject["streams"]!!.jsonArray.flatMap { s ->
                s.jsonObject["values"]!!.jsonArray.map { v -> v.jsonArray[0].jsonPrimitive.content.toLong() to v.jsonArray[1].jsonPrimitive.content }
            }
        }
        return values.map { it.second }.sortedBy { JSON.parseToJsonElement(it).jsonObject["seq"]!!.jsonPrimitive.content.toLong() }
    }
    override fun broken() = LokiSink(URI(base)).also { status = 500 }

    @Test
    fun `pushes to the Loki API with tenant, auth and low cardinality labels`() {
        sink().write(batch(4))
        assertTrue(requests.all { it.path == "/loki/api/v1/push" && it.headers["x-scope-orgid"] == "acme" && it.headers["authorization"]!!.startsWith("Basic ") })
        val labels = requests.flatMap { JSON.parseToJsonElement(it.body).jsonObject["streams"]!!.jsonArray.map { s -> s.jsonObject["stream"] as JsonObject } }
        assertTrue(labels.all { it.keys == setOf("app", "stream", "env", "category", "outcome") })
        assertEquals(setOf("success", "denied"), labels.map { it["outcome"]!!.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `timestamps are the record times in nanoseconds`() {
        val b = batch(2)
        sink().write(b)
        val stamps = requests.flatMap { r ->
            JSON.parseToJsonElement(r.body).jsonObject["streams"]!!.jsonArray.flatMap { s -> s.jsonObject["values"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content } }
        }.toSet()
        assertEquals(b.records.map { (it.time.epochSecond * 1_000_000_000L + it.time.nano).toString() }.toSet(), stamps)
    }
}

class SentinelSinkContractTest : HttpDestinationContract() {
    private val credential = object : CredentialProvider {
        var invalidated = 0
        override suspend fun token() = "entra-token"
        override fun invalidate() { invalidated++ }
    }

    override fun sink() = SentinelSink(URI(base), "dcr-123", "Custom-MaydayAudit_CL", credential)
    override fun received() = requests.flatMap { r ->
        (JSON.parseToJsonElement(r.body) as JsonArray).map { o -> JsonObject(o.jsonObject - "TimeGenerated").toString() }
    }
    override fun broken() = sink().also { status = 503 }

    @Test
    fun `posts to the data collection rule stream with an Entra token and TimeGenerated`() {
        val b = batch(3)
        sink().write(b)
        val r = requests.single()
        assertEquals("/dataCollectionRules/dcr-123/streams/Custom-MaydayAudit_CL", r.path)
        assertEquals("api-version=2023-01-01", r.query)
        assertEquals("Bearer entra-token", r.headers["authorization"])
        val rows = JSON.parseToJsonElement(r.body).jsonArray.map { it.jsonObject }
        assertEquals(b.records.map { it.time.toString() }, rows.map { it["TimeGenerated"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a refused token is fetched again on the next try`() {
        status = 401
        assertFailsWith<Exception> { sink().write(batch(1)) }
        assertEquals(1, credential.invalidated)
    }
}
