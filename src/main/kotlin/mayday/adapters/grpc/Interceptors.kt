package mayday.adapters.grpc

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import kotlinx.serialization.SerializationException
import mayday.adapters.auth.Authenticator
import mayday.identity.Principal
import mayday.shared.DomainException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.grpc.server.GlobalServerInterceptor
import org.springframework.stereotype.Component
import java.util.logging.Level
import java.util.logging.Logger

/** Raw credentials of the current call. Services resolve them lazily, so calls without credentials stay possible. */
data class Credentials(val authorization: String?, val cookie: String?)

object Grpc {
    val AUTHORIZATION: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    val COOKIE: Metadata.Key<String> = Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER)
    val ERROR_CODE: Metadata.Key<String> = Metadata.Key.of("mayday-error-code", Metadata.ASCII_STRING_MARSHALLER)
    val CREDENTIALS: Context.Key<Credentials> = Context.key("mayday-credentials")

    fun status(code: String): Status = when (code) {
        "not_found" -> Status.NOT_FOUND
        "forbidden" -> Status.PERMISSION_DENIED
        "locked" -> Status.FAILED_PRECONDITION
        "invalid" -> Status.INVALID_ARGUMENT
        "conflict" -> Status.ABORTED
        "unauthenticated" -> Status.UNAUTHENTICATED
        "unavailable" -> Status.UNAVAILABLE
        "resource_exhausted" -> Status.RESOURCE_EXHAUSTED
        else -> Status.INVALID_ARGUMENT
    }
}

suspend fun Authenticator.caller(): Principal {
    val c = Grpc.CREDENTIALS.get() ?: Credentials(null, null)
    return principal(c.authorization, c.cookie)
}

@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CredentialsInterceptor : ServerInterceptor {
    override fun <Q, R> interceptCall(call: ServerCall<Q, R>, headers: Metadata, next: ServerCallHandler<Q, R>): ServerCall.Listener<Q> {
        val ctx = Context.current().withValue(Grpc.CREDENTIALS, Credentials(headers.get(Grpc.AUTHORIZATION), headers.get(Grpc.COOKIE)))
        return Contexts.interceptCall(ctx, call, headers, next)
    }
}

/**
 * Maps domain errors to gRPC status codes. The domain code (for example "locked") is in the trailer mayday-error-code.
 * Other errors become INTERNAL with a generic message; the details go to the log only.
 */
@Component
@GlobalServerInterceptor
@Order(Ordered.HIGHEST_PRECEDENCE)
class ErrorInterceptor : ServerInterceptor {
    private val log = Logger.getLogger("mayday.grpc")

    override fun <Q, R> interceptCall(call: ServerCall<Q, R>, headers: Metadata, next: ServerCallHandler<Q, R>): ServerCall.Listener<Q> =
        next.startCall(object : ForwardingServerCall.SimpleForwardingServerCall<Q, R>(call) {
            override fun close(status: Status, trailers: Metadata) = super.close(translate(call, status, trailers), trailers)
        }, headers)

    private fun translate(call: ServerCall<*, *>, status: Status, trailers: Metadata): Status {
        if (status.isOk) return status
        val domain = when (val cause = status.cause) {
            is DomainException -> cause
            is SerializationException -> mayday.shared.Invalid(cause.message ?: "Invalid request")
            else -> null
        }
        if (domain != null) {
            trailers.put(Grpc.ERROR_CODE, domain.code)
            return Grpc.status(domain.code).withDescription(domain.message)
        }
        if (status.code == Status.Code.UNKNOWN) {
            log.log(Level.SEVERE, "${call.methodDescriptor.fullMethodName} failed", status.cause)
            return Status.INTERNAL.withDescription("Something went wrong. The error is logged.")
        }
        return status
    }
}
