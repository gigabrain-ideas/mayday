package mayday.contract

import mayday.RecordingSink
import mayday.audit.AuditActor
import mayday.audit.AuditBatch
import mayday.audit.AuditCategory
import mayday.audit.AuditChain
import mayday.audit.AuditEntry
import mayday.audit.AuditLog
import mayday.audit.AuditOutcome
import mayday.audit.AuditRecord
import mayday.audit.AuditSettings
import mayday.audit.AuditSink
import mayday.shared.JSON
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The audit stream port: order, completeness, tamper evidence, isolation of sinks and the reads switch. */
class AuditLogContractTest {
    private val key = AuditChain.key("log-contract")

    private fun entry(action: String, outcome: AuditOutcome = AuditOutcome.SUCCESS, read: Boolean = false) =
        AuditEntry(AuditCategory.AUTHORIZATION, action, AuditActor.system, outcome, read = read)

    private fun log(vararg sinks: AuditSink, settings: AuditSettings = AuditSettings(instance = "i/1")) = AuditLog(sinks.toList(), settings, key)

    @Test
    fun `records arrive numbered, chained and verifiable, starting with audit started`() {
        val sink = RecordingSink()
        val log = log(sink)
        repeat(1000) { log.emit(entry("a$it")) }
        assertTrue(log.flush())
        assertEquals("audit.started", sink.records.first().action)
        assertEquals((1L..1001L).toList(), sink.records.map { it.seq })
        assertEquals(List(1000) { "a$it" }, sink.records.drop(1).map { it.action })
        val report = AuditChain.verify(sink.lines.asSequence(), key)
        assertTrue(report.ok, report.problems.toString())
        log.close()
        assertEquals("audit.stopped", sink.records.last().action)
    }

    @Test
    fun `a changed, removed or reordered record is detected`() {
        val sink = RecordingSink()
        log(sink).apply { repeat(5) { emit(entry("a$it")) }; flush(); close() }
        val lines = sink.lines.toList()
        val changed = lines.toMutableList().also { it[2] = it[2].replace("\"a1\"", "\"a9\"") }
        assertFalse(AuditChain.verify(changed.asSequence(), key).ok)
        val removed = lines.filterIndexed { i, _ -> i != 3 }
        assertTrue(AuditChain.verify(removed.asSequence(), key).problems.any { "missing" in it.problem })
        val forged = lines.toMutableList().also {
            val r = JSON.decodeFromString(AuditRecord.serializer(), it[2])
            it[2] = JSON.encodeToString(AuditRecord.serializer(), r.copy(action = "x", hash = AuditChain.hash(AuditChain.key("other"), r.copy(action = "x"))))
        }
        assertFalse(AuditChain.verify(forged.asSequence(), key).ok, "a record signed with another key fails")
        assertTrue(AuditChain.verify(lines.reversed().asSequence(), key).ok, "arrival order does not matter")
        assertFalse(AuditChain.verify(lines.asSequence(), AuditChain.key("wrong")).ok)
    }

    @Test
    fun `allowed reads follow the reads switch, denied reads are always recorded`() {
        val sink = RecordingSink()
        val log = log(sink, settings = AuditSettings(reads = false, instance = "i/2"))
        log.emit(entry("read-ok", read = true))
        log.emit(entry("read-denied", AuditOutcome.DENIED, read = true))
        log.emit(entry("write"))
        log.flush()
        assertEquals(listOf("audit.started", "read-denied", "write"), sink.records.map { it.action })
    }

    @Test
    fun `a full queue drops new records and reports how many in the stream`() {
        val sink = RecordingSink()
        val log = log(sink, settings = AuditSettings(capacity = 4, instance = "i/3"))
        assertTrue(log.flush())
        val gate = CountDownLatch(1)
        log.hold = gate
        Thread.sleep(300)
        repeat(20) { log.emit(entry("e$it")) }
        log.hold = null
        gate.countDown()
        assertTrue(log.flush())
        assertEquals(16, log.stats().dropped)
        val reported = sink.records.filter { it.action == "audit.dropped" }
        assertEquals(listOf("16"), reported.map { it.details.getValue("count") })
        assertEquals(AuditOutcome.FAILURE, reported.single().outcome)
        assertEquals(listOf("e0", "e1", "e2", "e3"), sink.records.map { it.action }.filter { it.startsWith("e") })
        assertTrue(AuditChain.verify(sink.lines.asSequence(), key).ok)
    }

    @Test
    fun `a failing sink is retried and does not hold up the other sinks`() {
        val good = RecordingSink()
        val attempts = AtomicInteger()
        val flaky = RecordingSink()
        val failing = object : AuditSink {
            override val name = "flaky"
            override fun write(batch: AuditBatch) {
                if (attempts.incrementAndGet() <= 2) throw java.io.IOException("down")
                flaky.write(batch)
            }
        }
        val log = log(failing, good)
        repeat(10) { log.emit(entry("e$it")) }
        assertTrue(log.flush(15_000))
        assertEquals(11, good.records.size)
        assertEquals(good.records.map { it.id }, flaky.records.map { it.id })
        val stats = log.stats().sinks.first { it.name == "flaky" }
        assertTrue(stats.failures >= 2)
        assertEquals(0, stats.dropped)
    }

    @Test
    fun `emit never blocks the caller while sinks are down`() {
        val down = object : AuditSink {
            override val name = "down"
            override fun write(batch: AuditBatch) = throw java.io.IOException("down")
        }
        val log = log(down, settings = AuditSettings(capacity = 100_000, instance = "i/4"))
        val start = System.nanoTime()
        repeat(50_000) { log.emit(entry("e")) }
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "50,000 emits take less than 2 s")
    }

    @Test
    fun `each instance has its own chain`() {
        val sink = RecordingSink()
        log(sink, settings = AuditSettings(instance = "a/1")).apply { emit(entry("x")); flush(); close() }
        log(sink, settings = AuditSettings(instance = "b/1")).apply { emit(entry("y")); flush(); close() }
        val report = AuditChain.verify(sink.lines.asSequence(), key)
        assertTrue(report.ok, report.problems.toString())
        assertEquals(setOf("a/1", "b/1"), report.instances.keys)
    }

    @Test
    fun `the verify command accepts an intact export and refuses a changed one`() {
        val sink = RecordingSink()
        AuditLog(listOf(sink), AuditSettings(instance = "cli/1"), AuditChain.key("s3cret")).apply { emit(entry("x")); flush(); close() }
        val file = java.nio.file.Files.createTempFile("audit", ".jsonl")
        java.nio.file.Files.write(file, sink.lines)
        val out = java.io.ByteArrayOutputStream()
        assertEquals(0, mayday.audit.AuditVerify.main(arrayOf(file.toString()), mapOf("MAYDAY_SECRET_KEY" to "s3cret"), java.io.PrintStream(out)))
        assertTrue("OK" in out.toString())
        java.nio.file.Files.write(file, sink.lines.map { it.replace("\"x\"", "\"y\"") })
        assertEquals(1, mayday.audit.AuditVerify.main(arrayOf(file.toString()), mapOf("MAYDAY_SECRET_KEY" to "s3cret"), java.io.PrintStream(java.io.ByteArrayOutputStream())))
        assertEquals(2, mayday.audit.AuditVerify.main(arrayOf(file.toString()), emptyMap(), java.io.PrintStream(java.io.ByteArrayOutputStream())))
    }
}
