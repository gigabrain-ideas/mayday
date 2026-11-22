package mayday.adapters.grpc

import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Grpc as IoGrpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.kotlin.CoroutineContextServerInterceptor
import mayday.App
import mayday.audit.AuditActor
import mayday.audit.AuditCategory
import mayday.audit.AuditContext
import mayday.audit.AuditEntry
import mayday.audit.AuditOutcome
import mayday.audit.AuditSource
import mayday.audit.AuditTarget
import mayday.shared.DomainException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.grpc.server.GlobalServerInterceptor
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Headers that the gRPC-Web bridge sets on its in-process calls. Mayday ignores them on network calls. */
object AuditHeaders {
    val CHANNEL: Metadata.Key<String> = Metadata.Key.of("x-mayday-channel", Metadata.ASCII_STRING_MARSHALLER)
    val CLIENT_IP: Metadata.Key<String> = Metadata.Key.of("x-mayday-client-ip", Metadata.ASCII_STRING_MARSHALLER)
    val FORWARDED_FOR: Metadata.Key<String> = Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER)
    val USER_AGENT: Metadata.Key<String> = Metadata.Key.of("user-agent", Metadata.ASCII_STRING_MARSHALLER)
    /** The in-process transport replaces user-agent, so the bridge passes the browser's in its own header. */
    val CLIENT_USER_AGENT: Metadata.Key<String> = Metadata.Key.of("x-mayday-user-agent", Metadata.ASCII_STRING_MARSHALLER)
    val CONTEXT: Context.Key<AuditContext> = Context.key("mayday-audit")
}

/**
 * Records every gRPC and gRPC-Web call as an authorization decision: the method, the identifiers in the request,
 * the caller as the authenticator resolved it, and the result. Allowed reads follow MAYDAY_AUDIT_READS.
 * It runs inside [ErrorInterceptor], so it sees the domain error before the translation.
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class AuditInterceptor(private val app: App) : ServerInterceptor {
    override fun <Q, R> interceptCall(call: ServerCall<Q, R>, headers: Metadata, next: ServerCallHandler<Q, R>): ServerCall.Listener<Q> {
        val ctx = AuditContext(source(call, headers))
        val traceId = app.ports.telemetry.currentTraceId().ifEmpty { null }
        var request: Any? = null
        val audited = object : ForwardingServerCall.SimpleForwardingServerCall<Q, R>(call) {
            override fun close(status: Status, trailers: Metadata) {
                runCatching { record(call.methodDescriptor.fullMethodName, ctx, request, status, traceId) }
                super.close(status, trailers)
            }
        }
        val listener = Contexts.interceptCall(Context.current().withValue(AuditHeaders.CONTEXT, ctx), audited, headers, next)
        return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<Q>(listener) {
            override fun onMessage(message: Q) {
                if (request == null) request = message
                super.onMessage(message)
            }
        }
    }

    private fun source(call: ServerCall<*, *>, headers: Metadata): AuditSource {
        val remote = call.attributes.get(IoGrpc.TRANSPORT_ATTR_REMOTE_ADDR)
        if (remote is InetSocketAddress) return AuditSource("grpc", remote.address?.hostAddress ?: remote.hostString, null, headers.get(AuditHeaders.USER_AGENT)?.take(300))
        return AuditSource(
            headers.get(AuditHeaders.CHANNEL) ?: "grpc",
            headers.get(AuditHeaders.CLIENT_IP),
            headers.get(AuditHeaders.FORWARDED_FOR)?.take(300),
            headers.get(AuditHeaders.CLIENT_USER_AGENT)?.take(300),
        )
    }

    private fun record(fullMethod: String, ctx: AuditContext, request: Any?, status: Status, traceId: String?) {
        val service = fullMethod.substringAfterLast('.').substringBefore('/')
        val method = fullMethod.substringAfter('/')
        val code = (status.cause as? DomainException)?.code
        val closed = status.code == Status.Code.CANCELLED && code == null
        val outcome = when {
            status.isOk || closed -> AuditOutcome.SUCCESS
            status.code == Status.Code.PERMISSION_DENIED || status.code == Status.Code.UNAUTHENTICATED -> AuditOutcome.DENIED
            code == "forbidden" || code == "unauthenticated" || code == "locked" -> AuditOutcome.DENIED
            else -> AuditOutcome.FAILURE
        }
        val fields = (request as? Message)?.let(::identifiers).orEmpty()
        val targetId = fields["id"] ?: fields.entries.firstOrNull { it.key.endsWith("_ref") || it.key.endsWith("_id") }?.value
        app.audit.emit(AuditEntry(
            category = AuditCategory.AUTHORIZATION,
            action = "grpc:$service/$method",
            by = ctx.caller ?: AuditActor.anonymous,
            outcome = outcome,
            target = targetId?.let { AuditTarget(service.removeSuffix("Service").lowercase(), it) },
            details = fields.mapKeys { "req.${it.key}" } + if (closed) mapOf("status" to "cancelled") else emptyMap(),
            reason = if (outcome == AuditOutcome.SUCCESS) null else code ?: status.code.name.lowercase(),
            source = ctx.source,
            traceId = traceId,
            read = READS.any { method.startsWith(it) },
        ))
    }

    companion object {
        private val READS = listOf("Get", "List", "WhoAmI", "Subscribe")
        private val NAMES = setOf("id", "key", "scope", "team", "email", "action", "role", "enforcement")

        /** Identifiers and enumerated values of the request, never free text or secrets. */
        fun identifiers(m: Message): Map<String, String> = m.allFields.entries.asSequence()
            .filter { (f, _) -> !f.isRepeated && (f.name in NAMES || f.name.endsWith("_id") || f.name.endsWith("_ref")) }
            .mapNotNull { (f, v) ->
                when (f.javaType) {
                    Descriptors.FieldDescriptor.JavaType.STRING -> (v as String).takeIf { it.isNotEmpty() }?.take(200)
                    Descriptors.FieldDescriptor.JavaType.ENUM -> (v as Descriptors.EnumValueDescriptor).name
                    else -> null
                }?.let { f.name to it }
            }
            .toMap()
    }
}

/** Gives the coroutine of the call the [AuditContext], so the authenticator and the domain can fill it. */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
class AuditContextInterceptor : CoroutineContextServerInterceptor() {
    override fun coroutineContext(call: ServerCall<*, *>, headers: Metadata): CoroutineContext = AuditHeaders.CONTEXT.get() ?: EmptyCoroutineContext
}
