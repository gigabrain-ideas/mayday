package mayday.adapters.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mayday.identity.Principal
import mayday.shared.DomainEvent
import mayday.shared.DomainException
import mayday.shared.EventBus
import mayday.shared.JSON
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/** Live updates over Server-Sent Events. Each client has a bounded queue; slow clients lose events, not memory. */
class SseHub(private val bus: EventBus, private val maxClients: Int) {
    val clients = AtomicInteger()

    fun serve(ex: HttpExchange, principal: Principal) {
        mayday.identity.Access.requireRead(principal)
        if (clients.incrementAndGet() > maxClients) {
            clients.decrementAndGet()
            throw mayday.shared.Unavailable("Too many live connections")
        }
        val queue = LinkedBlockingQueue<DomainEvent>(256)
        val sub = bus.subscribe { queue.offer(it) }
        try {
            ex.responseHeaders.set("Content-Type", "text/event-stream")
            ex.responseHeaders.set("Cache-Control", "no-cache")
            ex.responseHeaders.set("X-Accel-Buffering", "no")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            out.write("retry: 3000\n\n".toByteArray()); out.flush()
            while (true) {
                val e = queue.poll(25, TimeUnit.SECONDS)
                val chunk = if (e == null) ": ping\n\n" else "event: ${e.type}\ndata: ${JSON.encodeToString(DomainEvent.serializer(), e)}\n\n"
                out.write(chunk.toByteArray())
                out.flush()
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            sub.close()
            clients.decrementAndGet()
            runCatching { ex.close() }
        }
    }
}

class Metrics {
    val requests = ConcurrentHashMap<String, AtomicLong>()
    val durationNanos = AtomicLong()
    val count = AtomicLong()

    fun record(status: Int, nanos: Long) {
        requests.computeIfAbsent("${status / 100}xx") { AtomicLong() }.incrementAndGet()
        durationNanos.addAndGet(nanos)
        count.incrementAndGet()
    }

    fun render(sse: Int): String = buildString {
        append("# TYPE mayday_http_requests_total counter\n")
        requests.forEach { (k, v) -> append("mayday_http_requests_total{code=\"$k\"} ${v.get()}\n") }
        append("# TYPE mayday_http_request_duration_seconds summary\n")
        append("mayday_http_request_duration_seconds_sum ${durationNanos.get() / 1e9}\n")
        append("mayday_http_request_duration_seconds_count ${count.get()}\n")
        append("# TYPE mayday_sse_clients gauge\nmayday_sse_clients $sse\n")
        val rt = Runtime.getRuntime()
        append("# TYPE mayday_jvm_memory_used_bytes gauge\nmayday_jvm_memory_used_bytes ${rt.totalMemory() - rt.freeMemory()}\n")
    }
}

/** Serves the built web interface from a directory or the classpath, with an SPA fallback. */
class StaticFiles(private val dir: Path?) {
    private val cache = ConcurrentHashMap<String, ByteArray>()

    private fun load(path: String): ByteArray? = cache[path] ?: run {
        val bytes = if (dir != null) {
            val f = dir.resolve(path.trimStart('/')).normalize()
            if (f.startsWith(dir) && Files.isRegularFile(f)) Files.readAllBytes(f) else null
        } else {
            javaClass.getResourceAsStream("/web/" + path.trimStart('/'))?.use { it.readAllBytes() }
        }
        bytes?.also { if (dir == null) cache[path] = it }
    }

    fun serve(path: String): BytesResponse? {
        val clean = if (path == "/" || path.isEmpty()) "/index.html" else path
        if (clean.contains("..")) return null
        val asset = load(clean)
        if (asset != null) {
            val cacheControl = if (clean.startsWith("/assets/")) "public, max-age=31536000, immutable" else "no-cache"
            return BytesResponse(asset, mime(clean), headers = mapOf("Cache-Control" to cacheControl))
        }
        if (clean.substringAfterLast('/').contains('.')) return null
        val index = load("/index.html") ?: return BytesResponse(PLACEHOLDER.toByteArray(), "text/html; charset=utf-8")
        return BytesResponse(index, "text/html; charset=utf-8", headers = mapOf("Cache-Control" to "no-cache"))
    }

    private fun mime(p: String) = when (p.substringAfterLast('.')) {
        "html" -> "text/html; charset=utf-8"; "js" -> "application/javascript"; "css" -> "text/css"
        "svg" -> "image/svg+xml"; "png" -> "image/png"; "ico" -> "image/x-icon"; "woff2" -> "font/woff2"
        "json" -> "application/json"; "webmanifest" -> "application/manifest+json"; else -> "application/octet-stream"
    }

    companion object {
        const val PLACEHOLDER = "<!doctype html><title>Mayday</title><p style='font-family:monospace'>The web interface is not built. Run <code>make web</code>.</p>"
    }
}

class Server(
    private val api: Api,
    private val static: StaticFiles,
    private val metrics: Metrics,
    private val sse: SseHub,
    private val ready: () -> Boolean,
) {
    private val log = Logger.getLogger("mayday.http")
    private lateinit var server: HttpServer

    fun start(host: String, port: Int): Int {
        server = HttpServer.create(InetSocketAddress(host, port), 4096)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        return server.address.port
    }

    fun stop() = server.stop(2)

    private fun handle(ex: HttpExchange) {
        val started = System.nanoTime()
        var status = 500
        try {
            val path = ex.requestURI.rawPath
            val gzip = ex.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true
            security(ex, path)
            val response: Response = when (path) {
                "/healthz" -> JsonResponse("""{"status":"ok"}""")
                "/readyz" -> if (ready()) JsonResponse("""{"status":"ready"}""") else JsonResponse("""{"status":"starting"}""", 503)
                "/metrics" -> BytesResponse(metrics.render(sse.clients.get()).toByteArray(), "text/plain; version=0.0.4")
                else -> route(ex, path)
            }
            status = if (response === Handled) 200 else response.status
            write(ex, response, gzip)
        } catch (e: Throwable) {
            status = statusOf(e)
            if (status == 500) log.severe("${ex.requestMethod} ${ex.requestURI.rawPath}: ${e.stackTraceToString()}")
            val code = (e as? DomainException)?.code ?: "internal"
            val msg = if (status == 500) "Something went wrong. The error is logged." else e.message ?: code
            runCatching { send(ex, status, errorBody(code, msg).toByteArray(), "application/json; charset=utf-8", false) }
        } finally {
            metrics.record(status, System.nanoTime() - started)
            runCatching { ex.close() }
        }
    }

    private fun route(ex: HttpExchange, path: String): Response {
        val (found, otherMethod) = api.router.match(ex.requestMethod, path)
        if (found != null) return found.first(Request(ex, found.second))
        if (otherMethod) return JsonResponse(errorBody("method_not_allowed", "Method not allowed"), 405)
        if (path.startsWith("/api/") || path == "/mcp" || path.startsWith("/auth/") || path.startsWith("/oauth/")) {
            return JsonResponse(errorBody("not_found", "Not found"), 404)
        }
        if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") return JsonResponse(errorBody("not_found", "Not found"), 404)
        return static.serve(path) ?: JsonResponse(errorBody("not_found", "Not found"), 404)
    }

    private fun security(ex: HttpExchange, path: String) {
        val h = ex.responseHeaders
        h.set("X-Content-Type-Options", "nosniff")
        h.set("Referrer-Policy", "same-origin")
        if (!path.startsWith("/api/") && path != "/mcp") {
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            h.set("X-Frame-Options", "DENY")
        }
    }

    private fun statusOf(e: Throwable) = when ((e as? DomainException)?.code) {
        "not_found" -> 404; "forbidden", "locked" -> 403; "invalid" -> 400; "conflict" -> 409
        "unauthenticated" -> 401; "unavailable" -> 503; null -> 500; else -> 400
    }
}
