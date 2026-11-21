package mayday.shared

enum class SpanKind { INTERNAL, CLIENT, SERVER, PRODUCER, CONSUMER }

/**
 * One open span. Attribute values are String, Boolean, Long, Int, Double or a List of String.
 * The attribute "mayday.link.traceparent" is not stored: the adapter adds a link to that trace.
 */
interface SpanScope {
    val traceId: String
    fun set(key: String, value: Any?)
    fun event(name: String, attributes: Map<String, Any?> = emptyMap())
    /** Marks the span as failed. The exception is also recorded when [error] is not null. */
    fun fail(message: String, error: Throwable? = null)
}

/**
 * Tracing port. The domain opens spans through it and stays free of a tracing library.
 * The span is current inside [block], also across suspension points, so child spans and
 * [propagationHeaders] see it.
 */
interface Telemetry {
    suspend fun <T> span(name: String, attributes: Map<String, Any?> = emptyMap(), kind: SpanKind = SpanKind.INTERNAL, block: suspend (SpanScope) -> T): T

    /** W3C trace context headers (traceparent, tracestate) of the current span, for outbound calls. */
    fun propagationHeaders(): Map<String, String>

    /** True when span content (prompts, responses, tool arguments) may be exported. */
    val captureContent: Boolean get() = false

    companion object {
        val noop: Telemetry = object : Telemetry {
            private val scope = object : SpanScope {
                override val traceId = ""
                override fun set(key: String, value: Any?) = Unit
                override fun event(name: String, attributes: Map<String, Any?>) = Unit
                override fun fail(message: String, error: Throwable?) = Unit
            }

            override suspend fun <T> span(name: String, attributes: Map<String, Any?>, kind: SpanKind, block: suspend (SpanScope) -> T): T = block(scope)
            override fun propagationHeaders(): Map<String, String> = emptyMap()
        }
    }
}
