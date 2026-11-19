package mayday.adapters.grpc

import io.grpc.BindableService
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.kotlin.ClientCalls
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.MediaType
import org.springframework.http.ReactiveHttpOutputMessage
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.server.RequestPredicates
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.ByteBuffer

/**
 * gRPC-Web for browsers on the HTTP port. Each call goes through an in-process channel to the gRPC server,
 * so browsers and native clients use the same services, interceptors and authentication.
 * Supported: binary gRPC-Web (application/grpc-web, application/grpc-web+proto), unary and server-streaming calls.
 */
class GrpcWebBridge(services: List<BindableService>, inProcessName: String) : DisposableBean {
    private val channel: ManagedChannel = InProcessChannelBuilder.forName(inProcessName).directExecutor().build()
    private val methods: Map<String, MethodDescriptor<ByteArray, ByteArray>> = services
        .flatMap { it.bindService().methods }
        .map { it.methodDescriptor }
        .filter { it.type == MethodDescriptor.MethodType.UNARY || it.type == MethodDescriptor.MethodType.SERVER_STREAMING }
        .associate { "/${it.fullMethodName}" to it.toBuilder(Bytes, Bytes).build() }
    private val buffers = DefaultDataBufferFactory.sharedInstance

    suspend fun handle(request: ServerRequest): ServerResponse {
        val method = methods[request.path()] ?: return ServerResponse.notFound().buildAndAwait()
        val message = unframe(request.awaitBody<ByteArray>()) ?: return ServerResponse.badRequest().buildAndAwait()
        val headers = Metadata()
        request.headers().firstHeader("Authorization")?.let { headers.put(Grpc.AUTHORIZATION, it) }
        request.headers().firstHeader("Cookie")?.let { headers.put(Grpc.COOKIE, it) }

        val replies: Flow<ByteArray> = when (method.type) {
            MethodDescriptor.MethodType.UNARY -> flow { emit(ClientCalls.unaryRpc(channel, method, message, headers = headers)) }
            else -> ClientCalls.serverStreamingRpc(channel, method, message, headers = headers)
        }
        var trailer: ByteArray = trailerFrame(Status.OK, Metadata())
        val frames = flow {
            replies.catch { e ->
                val se = e as? StatusException ?: StatusException(Status.fromThrowable(e))
                trailer = trailerFrame(se.status, se.trailers ?: Metadata())
            }.collect { emit(frame(0, it)) }
            emit(trailer)
        }

        val response = ServerResponse.ok().contentType(GRPC_WEB_PROTO).header("Cache-Control", "no-store")
        if (method.type == MethodDescriptor.MethodType.UNARY) {
            val out = java.io.ByteArrayOutputStream()
            frames.collect { out.write(it) }
            return response.contentLength(out.size().toLong()).bodyValue(out.toByteArray()).awaitSingle()
        }
        return response.header("X-Accel-Buffering", "no").body(flushEach(frames.map { buffers.wrap(it) as DataBuffer }.asFlux())).awaitSingle()
    }

    override fun destroy() {
        channel.shutdownNow()
    }

    private fun flushEach(frames: Flux<DataBuffer>) =
        BodyInserter<Flux<DataBuffer>, ReactiveHttpOutputMessage> { out, _ -> out.writeAndFlushWith(frames.map { Mono.just(it) }) }

    private fun unframe(body: ByteArray): ByteArray? {
        if (body.size < 5 || body[0].toInt() and 0x80 != 0) return null
        val len = ByteBuffer.wrap(body, 1, 4).int
        if (len < 0 || len > body.size - 5) return null
        return body.copyOfRange(5, 5 + len)
    }

    private fun frame(flag: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(5 + payload.size).put(flag.toByte()).putInt(payload.size).put(payload).array()

    private fun trailerFrame(status: Status, trailers: Metadata): ByteArray {
        val text = buildString {
            append("grpc-status: ${status.code.value()}\r\n")
            status.description?.let { append("grpc-message: ${URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")}\r\n") }
            trailers.keys().filter { !it.endsWith(Metadata.BINARY_HEADER_SUFFIX) }.forEach { k ->
                trailers.get(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER))?.let { append("$k: $it\r\n") }
            }
        }
        return frame(0x80, text.toByteArray(Charsets.US_ASCII))
    }

    private object Bytes : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)
        override fun parse(stream: InputStream): ByteArray = stream.readAllBytes()
    }

    companion object {
        val GRPC_WEB_PROTO = MediaType("application", "grpc-web+proto")
    }
}

@Configuration(proxyBeanMethods = false)
class GrpcWebRoutes {
    @Bean
    fun grpcWebBridge(services: List<BindableService>, @Value("\${spring.grpc.server.inprocess.name}") name: String) = GrpcWebBridge(services, name)

    @Bean
    fun grpcWebRouter(bridge: GrpcWebBridge) = coRouter {
        val grpcWeb = RequestPredicates.headers { h -> h.contentType().map { it.type == "application" && it.subtype in SUBTYPES }.orElse(false) }
        (POST("/{service:mayday\\.v1\\.[A-Za-z]+}/{method}") and grpcWeb).invoke(bridge::handle)
    }

    private companion object {
        val SUBTYPES = setOf("grpc-web", "grpc-web+proto")
    }
}
