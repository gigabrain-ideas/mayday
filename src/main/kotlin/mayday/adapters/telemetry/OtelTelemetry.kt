package mayday.adapters.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import mayday.VERSION
import mayday.shared.DomainException
import mayday.shared.SpanKind
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import io.opentelemetry.api.trace.SpanKind as OtelKind

/**
 * OpenTelemetry adapter of the [Telemetry] port. The span is current in the coroutine context of the block,
 * so it stays the parent across suspension points and dispatcher changes.
 *
 * The attribute [LINK] (a W3C traceparent) is not stored: it becomes a span link. Background work uses it to
 * point to the request that started it, without making the request trace as long as the work.
 */
class OtelTelemetry(openTelemetry: OpenTelemetry, override val captureContent: Boolean = false) : Telemetry {
    private val tracer = openTelemetry.getTracer("mayday", VERSION)
    private val w3c = W3CTraceContextPropagator.getInstance()

    override suspend fun <T> span(name: String, attributes: Map<String, Any?>, kind: SpanKind, block: suspend (SpanScope) -> T): T {
        val parent = Context.current()
        val builder = tracer.spanBuilder(name).setParent(parent).setSpanKind(KINDS[kind.ordinal])
        for ((k, v) in attributes) {
            if (v == null) continue
            if (k == LINK) link(builder, v.toString()) else builder.put(k, v)
        }
        val span = builder.startSpan()
        try {
            return withContext(parent.with(span).asContextElement()) { block(Scope(span)) }
        } catch (e: CancellationException) {
            span.setAttribute(CANCELLED, true)
            throw e
        } catch (e: Throwable) {
            span.setAttribute(ERROR_TYPE, (e as? DomainException)?.code ?: e.javaClass.name)
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            span.end()
        }
    }

    override fun propagationHeaders(): Map<String, String> {
        val ctx = Context.current()
        if (!Span.fromContext(ctx).spanContext.isValid) return emptyMap()
        val out = HashMap<String, String>(4)
        w3c.inject(ctx, out, SETTER)
        return out
    }

    private fun link(builder: SpanBuilder, traceparent: String) {
        val ctx = Span.fromContext(w3c.extract(Context.root(), traceparent, GETTER)).spanContext
        if (ctx.isValid) builder.addLink(ctx)
    }

    private class Scope(private val span: Span) : SpanScope {
        override val traceId: String = span.spanContext.let { if (it.isValid && it.isSampled) it.traceId else "" }
        override fun set(key: String, value: Any?) {
            if (value != null && span.isRecording) span.put(key, value)
        }

        override fun event(name: String, attributes: Map<String, Any?>) {
            if (!span.isRecording) return
            if (attributes.isEmpty()) span.addEvent(name)
            else span.addEvent(name, Attributes.builder().also { b -> attributes.forEach { (k, v) -> if (v != null) b.put(k, v) } }.build())
        }

        override fun fail(message: String, error: Throwable?) {
            span.setStatus(StatusCode.ERROR, message)
            if (error != null) {
                span.setAttribute(ERROR_TYPE, (error as? DomainException)?.code ?: error.javaClass.name)
                span.recordException(error)
            }
        }
    }

    companion object {
        const val LINK = "mayday.link.traceparent"
        private val KINDS = arrayOf(OtelKind.INTERNAL, OtelKind.CLIENT, OtelKind.SERVER, OtelKind.PRODUCER, OtelKind.CONSUMER)
        private val ERROR_TYPE = AttributeKey.stringKey("error.type")
        private val CANCELLED = AttributeKey.booleanKey("mayday.cancelled")

        private val SETTER = TextMapSetter<MutableMap<String, String>> { carrier, key, value -> carrier!![key] = value }
        private val GETTER = object : TextMapGetter<String> {
            override fun keys(carrier: String) = listOf("traceparent")
            override fun get(carrier: String?, key: String) = if (key == "traceparent") carrier else null
        }

        /** Maps a port value to a typed OpenTelemetry attribute. */
        @Suppress("UNCHECKED_CAST")
        private fun typed(key: String, value: Any): Pair<AttributeKey<Any>, Any> = when (value) {
            is String -> AttributeKey.stringKey(key) to value
            is Boolean -> AttributeKey.booleanKey(key) to value
            is Long -> AttributeKey.longKey(key) to value
            is Int -> AttributeKey.longKey(key) to value.toLong()
            is Double -> AttributeKey.doubleKey(key) to value
            is Float -> AttributeKey.doubleKey(key) to value.toDouble()
            is List<*> -> AttributeKey.stringArrayKey(key) to value.mapNotNull { it?.toString() }
            is Enum<*> -> AttributeKey.stringKey(key) to value.name
            else -> AttributeKey.stringKey(key) to value.toString()
        } as Pair<AttributeKey<Any>, Any>

        private fun SpanBuilder.put(key: String, value: Any) = typed(key, value).let { (k, v) -> setAttribute(k, v) }
        private fun Span.put(key: String, value: Any) = typed(key, value).let { (k, v) -> setAttribute(k, v) }
        private fun AttributesBuilder.put(key: String, value: Any) = typed(key, value).let { (k, v) -> put(k, v) }
    }
}
