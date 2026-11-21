package mayday.contract

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mayday.adapters.telemetry.MaydaySampler
import mayday.adapters.telemetry.OtelTelemetry
import mayday.adapters.telemetry.TelemetryConfiguration
import mayday.shared.Forbidden
import mayday.shared.SpanKind
import mayday.shared.Telemetry
import mayday.suspendTest
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract of the Telemetry port, checked against the OpenTelemetry adapter with an in-memory exporter. */
class TelemetryContractTest {
    private val exporter = InMemorySpanExporter.create()
    private fun sdk(sampler: Sampler = Sampler.alwaysOn()) = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().setSampler(sampler).addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
        .build()
    private val sdk = sdk()
    private val telemetry: Telemetry = OtelTelemetry(sdk)

    @AfterEach
    fun close() = sdk.close()

    private fun span(name: String): SpanData = exporter.finishedSpanItems.single { it.name == name }

    @Test
    fun `child spans nest across suspension and dispatcher switches`() = suspendTest {
        telemetry.span("parent") {
            delay(5)
            withContext(Dispatchers.IO) { telemetry.span("io child") { delay(5) } }
            withContext(Dispatchers.Default) { delay(5); telemetry.span("default child") { } }
            coroutineScope { launch { telemetry.span("launched child") { } } }
        }
        val parent = span("parent")
        for (name in listOf("io child", "default child", "launched child")) {
            assertEquals(parent.spanId, span(name).parentSpanId, name)
            assertEquals(parent.traceId, span(name).traceId, name)
        }
    }

    @Test
    fun `parallel spans do not leak into each other`() = suspendTest {
        telemetry.span("root") { coroutineScope {
            val a = async(Dispatchers.Default) { telemetry.span("a") { delay(20); telemetry.span("a1") { } } }
            val b = async(Dispatchers.Default) { telemetry.span("b") { delay(10); telemetry.span("b1") { } } }
            a.await(); b.await()
        } }
        assertEquals(span("a").spanId, span("a1").parentSpanId)
        assertEquals(span("b").spanId, span("b1").parentSpanId)
        assertEquals(span("root").spanId, span("a").parentSpanId)
    }

    @Test
    fun `attributes keep their types and null values are skipped`() = suspendTest {
        telemetry.span("typed", mapOf("s" to "x", "b" to true, "l" to 7L, "i" to 3, "d" to 1.5, "list" to listOf("a", "b"), "n" to null), SpanKind.CLIENT) {
            it.set("late", 9)
            it.set("skipped", null)
            it.event("happened", mapOf("k" to "v", "z" to null))
        }
        val s = span("typed")
        assertEquals(io.opentelemetry.api.trace.SpanKind.CLIENT, s.kind)
        assertEquals("x", s.attributes.get(AttributeKey.stringKey("s")))
        assertEquals(true, s.attributes.get(AttributeKey.booleanKey("b")))
        assertEquals(7L, s.attributes.get(AttributeKey.longKey("l")))
        assertEquals(3L, s.attributes.get(AttributeKey.longKey("i")))
        assertEquals(1.5, s.attributes.get(AttributeKey.doubleKey("d")))
        assertEquals(listOf("a", "b"), s.attributes.get(AttributeKey.stringArrayKey("list")))
        assertEquals(9L, s.attributes.get(AttributeKey.longKey("late")))
        assertNull(s.attributes.get(AttributeKey.stringKey("n")))
        assertNull(s.attributes.get(AttributeKey.stringKey("skipped")))
        assertEquals("v", s.events.single { it.name == "happened" }.attributes.get(AttributeKey.stringKey("k")))
    }

    @Test
    fun `a failing block marks the span as error and rethrows`() = suspendTest {
        assertFailsWith<Forbidden> { telemetry.span("denied") { throw Forbidden("no") } }
        val s = span("denied")
        assertEquals(StatusCode.ERROR, s.status.statusCode)
        assertEquals("forbidden", s.attributes.get(AttributeKey.stringKey("error.type")))
        assertTrue(s.events.any { it.name == "exception" })
    }

    @Test
    fun `fail marks the span as error without an exception`() = suspendTest {
        telemetry.span("soft") { it.fail("tool returned an error") }
        assertEquals(StatusCode.ERROR, span("soft").status.statusCode)
        assertEquals("tool returned an error", span("soft").status.description)
    }

    @Test
    fun `cancellation is not an error`() = suspendTest {
        val job = launch(Dispatchers.Default) { telemetry.span("cancelled") { awaitCancellation() } }
        delay(50)
        job.cancelAndJoin()
        val s = span("cancelled")
        assertEquals(StatusCode.UNSET, s.status.statusCode)
        assertTrue(s.hasEnded())
        assertFailsWith<CancellationException> { telemetry.span("cancel inside") { throw CancellationException("stop") } }
        assertEquals(StatusCode.UNSET, span("cancel inside").status.statusCode)
    }

    @Test
    fun `propagation headers carry the current span and trace id matches`() = suspendTest {
        assertEquals(emptyMap(), telemetry.propagationHeaders())
        var headers = emptyMap<String, String>()
        var traceId = ""
        telemetry.span("outbound", kind = SpanKind.CLIENT) {
            traceId = it.traceId
            headers = withContext(Dispatchers.IO) { telemetry.propagationHeaders() }
        }
        val s = span("outbound")
        assertEquals(s.traceId, traceId)
        val traceparent = headers.getValue("traceparent")
        assertTrue(traceparent.startsWith("00-${s.traceId}-${s.spanId}-"), traceparent)
        assertEquals(1, traceparent.takeLast(2).toInt(16) and 1, "sampled flag")
    }

    @Test
    fun `a traceparent link attribute becomes a span link`() = suspendTest {
        var parent = ""
        telemetry.span("request") { parent = telemetry.propagationHeaders().getValue("traceparent") }
        telemetry.span("background", mapOf(OtelTelemetry.LINK to parent)) { }
        val bg = span("background")
        val req = span("request")
        assertTrue(bg.traceId != req.traceId)
        assertEquals(req.spanId, bg.links.single().spanContext.spanId)
        assertNull(bg.attributes.get(AttributeKey.stringKey(OtelTelemetry.LINK)))
    }

    @Test
    fun `unsampled spans have no trace id and the noop port runs the block`() = suspendTest {
        val off = sdk(Sampler.alwaysOff())
        try {
            assertEquals("", OtelTelemetry(off).span("dropped") { it.traceId })
        } finally {
            off.close()
        }
        assertEquals(42, Telemetry.noop.span("noop") { assertEquals("", it.traceId); 42 })
        assertEquals(emptyMap(), Telemetry.noop.propagationHeaders())
    }

    @Test
    fun `content capture is off unless enabled`() {
        assertEquals(false, telemetry.captureContent)
        assertEquals(true, OtelTelemetry(sdk, captureContent = true).captureContent)
    }

    @Test
    fun `agent runs are sampled even when the ratio drops other traces`() = suspendTest {
        val sampled = sdk(MaydaySampler(0.0, agentRuns = true))
        try {
            val t = OtelTelemetry(sampled)
            val run = t.span("invoke_agent Mayday agent") { r -> t.span("chat") { }; r.traceId }
            assertTrue(run.isNotEmpty())
            assertEquals("", t.span("GET /api") { it.traceId })
            assertEquals("", OtelTelemetry(sdk(MaydaySampler(0.0, agentRuns = false))).span("invoke_agent x") { it.traceId })
            assertTrue(exporter.finishedSpanItems.any { it.name == "chat" })
        } finally {
            sampled.close()
        }
    }

    @Test
    fun `the OTLP base URL gets the signal path for HTTP only`() {
        assertEquals("http://collector:4318/v1/traces", TelemetryConfiguration.signalUrl("http://collector:4318/", true, "traces"))
        assertEquals("http://collector:4318/v1/traces", TelemetryConfiguration.signalUrl("http://collector:4318/v1/traces", true, "traces"))
        assertEquals("http://collector:4317", TelemetryConfiguration.signalUrl("http://collector:4317", false, "traces"))
    }
}
