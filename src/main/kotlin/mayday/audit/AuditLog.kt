package mayday.audit

import mayday.shared.Clock
import mayday.shared.Crypto
import mayday.shared.Ids
import mayday.shared.JSON
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

data class AuditSettings(
    /** Record allowed reads. Denied reads are always recorded. */
    val reads: Boolean = true,
    /** Records that wait for the writer. When the queue is full, Mayday drops new records and reports the count. */
    val capacity: Int = 65_536,
    val batchSize: Int = 500,
    /** Records that wait per sink while the sink is down. Then the oldest batches are dropped. */
    val sinkBuffer: Int = 200_000,
    val instance: String = defaultInstance(),
) {
    companion object {
        /** Host (pod) name and a boot id: each process start begins a new hash chain. */
        fun defaultInstance(): String {
            val host = System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
                ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("mayday")
            return "$host/${Ids.new().takeLast(8)}"
        }
    }
}

@kotlinx.serialization.Serializable
data class SinkStats(val name: String, val delivered: Long, val failures: Long, val dropped: Long, val pending: Long, val lastError: String?)
data class AuditStats(val accepted: Long, val dropped: Long, val sinks: List<SinkStats>)

object AuditChain {
    /** The audit key: MAYDAY_AUDIT_KEY, else derived from MAYDAY_SECRET_KEY. */
    fun key(secretKey: String, auditKey: String = ""): ByteArray =
        if (auditKey.isNotEmpty()) auditKey.toByteArray() else Crypto.hmac(secretKey.toByteArray(), "mayday-audit-v1".toByteArray())

    fun hash(key: ByteArray, record: AuditRecord): String =
        Crypto.hex(Crypto.hmac(key, JSON.encodeToString(AuditRecord.serializer(), record.copy(hash = null)).toByteArray()))

    data class Problem(val instance: String, val seq: Long, val problem: String)
    data class Report(val records: Int, val instances: Map<String, LongRange>, val problems: List<Problem>, val unreadable: Int) {
        val ok get() = problems.isEmpty() && unreadable == 0
    }

    /**
     * Checks every record's hash, the links between records and gaps in the sequence, per instance.
     * The lines can come in any order (for example from a SIEM export). A chain that does not start at 1 is not a problem:
     * the export can begin in the middle.
     */
    fun verify(lines: Sequence<String>, key: ByteArray): Report {
        var unreadable = 0
        val byInstance = HashMap<String, MutableList<AuditRecord>>()
        var count = 0
        for (line in lines) {
            if (line.isBlank()) continue
            val r = runCatching { JSON.decodeFromString(AuditRecord.serializer(), line.trim()) }.getOrNull()
            if (r == null || r.schema != AUDIT_SCHEMA) { unreadable++; continue }
            byInstance.getOrPut(r.instance) { ArrayList() } += r
            count++
        }
        val problems = ArrayList<Problem>()
        val ranges = LinkedHashMap<String, LongRange>()
        for ((instance, records) in byInstance.toSortedMap()) {
            records.sortBy { it.seq }
            ranges[instance] = records.first().seq..records.last().seq
            var before: AuditRecord? = null
            for (r in records) {
                if (r.hash != hash(key, r)) problems += Problem(instance, r.seq, "hash does not match: the record was changed or the key is wrong")
                if (r.seq == 1L && r.prev.isNotEmpty()) problems += Problem(instance, r.seq, "first record has a previous hash")
                val b = before
                if (b != null) {
                    when {
                        r.seq == b.seq -> problems += Problem(instance, r.seq, "duplicate sequence number")
                        r.seq > b.seq + 1 -> problems += Problem(instance, r.seq, "records ${b.seq + 1} to ${r.seq - 1} are missing")
                        r.prev != b.hash -> problems += Problem(instance, r.seq, "previous hash does not match record ${b.seq}")
                    }
                }
                before = r
            }
        }
        return Report(count, ranges, problems, unreadable)
    }
}

/**
 * The audit stream. [emit] puts the entry in a bounded queue and returns. One writer thread numbers the records,
 * chains them with HMAC-SHA256 and hands each batch to every sink. Each sink has its own thread, buffer and retries,
 * so a slow or failing sink does not hold up the others or the requests.
 */
class AuditLog(
    sinks: List<AuditSink>,
    private val settings: AuditSettings,
    private val key: ByteArray,
    private val clock: Clock = Clock.system,
    private val traceId: () -> String? = { null },
    details: Map<String, String> = emptyMap(),
) : AuditTrail, AutoCloseable {
    private val log = Logger.getLogger("mayday.audit")
    private val queue = ArrayBlockingQueue<Pending>(settings.capacity)
    private val accepted = AtomicLong()
    private val dropped = AtomicLong()
    private val droppedReported = AtomicLong()
    private val workers = sinks.map { Worker(it) }
    private var seq = 0L
    private var prev = ""
    private val unhanded = AtomicLong()
    @Volatile private var running = true
    /** Test seam: while set, the writer waits before it takes records from the queue. */
    @Volatile internal var hold: java.util.concurrent.CountDownLatch? = null
    private val writer = Thread(::writeLoop, "mayday-audit").apply { isDaemon = true; start() }

    init {
        emit(AuditEntry(AuditCategory.SYSTEM, "audit.started", AuditActor.system,
            details = details + mapOf("sinks" to sinks.joinToString(",") { it.name }, "reads" to settings.reads.toString())))
    }

    val instance: String get() = settings.instance
    val reads: Boolean get() = settings.reads

    override fun emit(entry: AuditEntry) {
        if (!running) return
        if (entry.read && entry.outcome == AuditOutcome.SUCCESS && !settings.reads) return
        val pending = Pending(entry, clock.now(), entry.traceId ?: runCatching { traceId() }.getOrNull()?.takeIf { it.isNotEmpty() })
        unhanded.incrementAndGet()
        if (queue.offer(pending)) accepted.incrementAndGet() else { unhanded.decrementAndGet(); dropped.incrementAndGet() }
    }

    fun stats() = AuditStats(accepted.get(), dropped.get(), workers.map { it.stats() })

    /** Waits until every accepted record reached every sink or [timeoutMs] passed. Returns true when all were delivered. */
    fun flush(timeoutMs: Long = 10_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (unhanded.get() == 0L && workers.all { it.idle }) return true
            Thread.sleep(2)
        }
        return false
    }

    override fun close() {
        if (!running) return
        emit(AuditEntry(AuditCategory.SYSTEM, "audit.stopped", AuditActor.system))
        flush()
        running = false
        writer.interrupt()
        workers.forEach { it.stop() }
    }

    private fun writeLoop() {
        val batch = ArrayList<Pending>(settings.batchSize)
        while (running || queue.isNotEmpty()) {
            try {
                hold?.await()
                val first = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                batch += first
                queue.drainTo(batch, settings.batchSize - 1)
                val out = ArrayList<AuditRecord>(batch.size + 1)
                val lost = dropped.get() - droppedReported.get()
                if (lost > 0) {
                    droppedReported.addAndGet(lost)
                    out += stamp(Pending(AuditEntry(AuditCategory.SYSTEM, "audit.dropped", AuditActor.system, AuditOutcome.FAILURE,
                        reason = "queue_full", details = mapOf("count" to lost.toString())), clock.now(), null))
                }
                batch.forEach { out += stamp(it) }
                val lines = out.map { JSON.encodeToString(AuditRecord.serializer(), it) }
                val b = AuditBatch(out, lines)
                workers.forEach { it.offer(b) }
            } catch (_: InterruptedException) {
                if (!running && queue.isEmpty()) break
            } catch (e: Exception) {
                log.log(Level.SEVERE, "Audit writer failed", e)
            } finally {
                unhanded.addAndGet(-batch.size.toLong())
                batch.clear()
            }
        }
    }

    private fun stamp(p: Pending): AuditRecord {
        val e = p.entry
        val a = e.by
        seq++
        val r = AuditRecord(
            id = Ids.new(), time = p.time, instance = settings.instance, seq = seq,
            category = e.category, action = e.action, outcome = e.outcome, reason = e.reason,
            actor = a.actor, onBehalfOf = a.onBehalfOf, role = a.role, scopes = a.scopes, runId = a.runId, credential = a.credential,
            target = e.target, teams = e.teams, source = e.source ?: AuditSource.internal, traceId = p.traceId,
            details = e.details, prev = prev,
        )
        val h = AuditChain.hash(key, r)
        prev = h
        return r.copy(hash = h)
    }

    private inner class Worker(private val sink: AuditSink) {
        private val batches = LinkedBlockingDeque<AuditBatch>()
        private val pending = AtomicLong()
        private val delivered = AtomicLong()
        private val failures = AtomicLong()
        private val lost = AtomicLong()
        @Volatile private var lastError: String? = null
        @Volatile private var writing = false
        @Volatile private var alive = true
        private val thread = Thread(::loop, "mayday-audit-${sink.name}").apply { isDaemon = true; start() }

        val idle get() = pending.get() == 0L && !writing

        fun offer(b: AuditBatch) {
            synchronized(this) {
                while (pending.get() + b.size > settings.sinkBuffer) {
                    val old = batches.pollFirst() ?: break
                    pending.addAndGet(-old.size.toLong())
                    lost.addAndGet(old.size.toLong())
                }
                pending.addAndGet(b.size.toLong())
                batches.addLast(b)
            }
        }

        fun stats() = SinkStats(sink.name, delivered.get(), failures.get(), lost.get(), pending.get(), lastError)

        fun stop() {
            alive = false
            thread.interrupt()
        }

        private fun loop() {
            while (alive) {
                val b = try { batches.takeFirst() } catch (_: InterruptedException) { break }
                writing = true
                var attempt = 0
                while (true) {
                    try {
                        sink.write(b)
                        delivered.addAndGet(b.size.toLong())
                        if (lastError != null) log.info("Audit sink ${sink.name} delivers again")
                        lastError = null
                        break
                    } catch (e: Exception) {
                        failures.incrementAndGet()
                        if (lastError == null) log.log(Level.WARNING, "Audit sink ${sink.name} failed; Mayday retries", e)
                        lastError = e.javaClass.simpleName + (e.message?.let { ": ${it.take(200)}" } ?: "")
                        if (!alive || !running && attempt >= 2) { lost.addAndGet(b.size.toLong()); break }
                        try { Thread.sleep(backoff(attempt++)) } catch (_: InterruptedException) { lost.addAndGet(b.size.toLong()); break }
                    }
                }
                synchronized(this) { pending.addAndGet(-b.size.toLong()) }
                writing = false
            }
        }

        private fun backoff(attempt: Int) = minOf(30_000L, 250L shl minOf(attempt, 7))
    }
}
