package mayday.adapters.http

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import mayday.shared.DomainException
import mayday.shared.Invalid
import mayday.shared.JSON
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.GZIPOutputStream

class Request(val ex: HttpExchange, val params: Map<String, String>) {
    val method: String get() = ex.requestMethod
    val path: String get() = ex.requestURI.rawPath
    val query: Map<String, List<String>> by lazy { parseQuery(ex.requestURI.rawQuery) }
    private var bodyCache: String? = null

    fun header(name: String): String? = ex.requestHeaders.getFirst(name)
    fun q(name: String): String? = query[name]?.firstOrNull()?.takeIf { it.isNotEmpty() }
    fun qList(name: String): List<String> = query[name].orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    fun param(name: String) = params[name] ?: throw Invalid("Missing path parameter $name")

    fun body(): String {
        bodyCache?.let { return it }
        val bytes = ex.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) throw Invalid("The request body is too large")
        return String(bytes, Charsets.UTF_8).also { bodyCache = it }
    }

    inline fun <reified T> json(): T = decode(serializer<T>())

    fun <T> decode(ser: KSerializer<T>): T {
        val b = body()
        if (b.isBlank()) throw Invalid("A JSON body is required")
        return try { JSON.decodeFromString(ser, b) } catch (e: DomainException) { throw e } catch (e: IllegalArgumentException) {
            throw Invalid("Invalid JSON: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}")
        }
    }

    fun cookie(name: String): String? = ex.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }
        .map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    companion object {
        const val MAX_BODY = 2 * 1024 * 1024

        fun parseQuery(raw: String?): Map<String, List<String>> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split('&').filter { it.isNotEmpty() }.map {
                val k = URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8)
                val v = URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                k to v
            }.groupBy({ it.first }, { it.second })
        }
    }
}

sealed interface Response {
    val status: Int
    val headers: Map<String, String>
}

class JsonResponse(val body: String, override val status: Int = 200, override val headers: Map<String, String> = emptyMap()) : Response
class BytesResponse(val body: ByteArray, val contentType: String, override val status: Int = 200, override val headers: Map<String, String> = emptyMap()) : Response
class Redirect(val location: String, override val headers: Map<String, String> = emptyMap()) : Response { override val status = 302 }
object NoContent : Response { override val status = 204; override val headers = emptyMap<String, String>() }
/** The handler wrote the response itself (streams). */
object Handled : Response { override val status = 0; override val headers = emptyMap<String, String>() }

inline fun <reified T> ok(value: T, status: Int = 200, headers: Map<String, String> = emptyMap()) =
    JsonResponse(JSON.encodeToString(serializer<T>(), value), status, headers)

fun errorBody(code: String, message: String) = JSON.encodeToString(JsonElement.serializer(), buildJsonObject {
    put("error", code); put("message", message)
})

typealias Handler = (Request) -> Response

class Router {
    private class Route(val method: String, val parts: List<String>, val handler: Handler)
    private val routes = mutableListOf<Route>()

    fun add(method: String, pattern: String, handler: Handler) {
        routes += Route(method, pattern.trim('/').split('/'), handler)
    }

    fun get(p: String, h: Handler) = add("GET", p, h)
    fun post(p: String, h: Handler) = add("POST", p, h)
    fun put(p: String, h: Handler) = add("PUT", p, h)
    fun patch(p: String, h: Handler) = add("PATCH", p, h)
    fun delete(p: String, h: Handler) = add("DELETE", p, h)

    /** Returns the handler and path params, or null. Second value is true when the path exists with another method. */
    fun match(method: String, path: String): Pair<Pair<Handler, Map<String, String>>?, Boolean> {
        val segs = path.trim('/').split('/').map { URLDecoder.decode(it, Charsets.UTF_8) }
        var otherMethod = false
        for (r in routes) {
            if (r.parts.size != segs.size) continue
            val params = HashMap<String, String>()
            var okMatch = true
            for (i in segs.indices) {
                val p = r.parts[i]
                if (p.startsWith(':')) params[p.substring(1)] = segs[i]
                else if (p != segs[i]) { okMatch = false; break }
            }
            if (!okMatch) continue
            if (r.method != method) { otherMethod = true; continue }
            return (r.handler to params) to false
        }
        return null to otherMethod
    }
}

fun write(ex: HttpExchange, r: Response, gzipOk: Boolean) {
    r.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
    when (r) {
        is JsonResponse -> send(ex, r.status, r.body.toByteArray(), "application/json; charset=utf-8", gzipOk)
        is BytesResponse -> send(ex, r.status, r.body, r.contentType, gzipOk)
        is Redirect -> { ex.responseHeaders.set("Location", r.location); ex.sendResponseHeaders(302, -1) }
        NoContent -> ex.sendResponseHeaders(204, -1)
        Handled -> Unit
    }
}

private val compressible = listOf("application/json", "text/", "application/javascript", "image/svg+xml")

fun send(ex: HttpExchange, status: Int, body: ByteArray, contentType: String, gzipOk: Boolean) {
    ex.responseHeaders.set("Content-Type", contentType)
    var out = body
    if (gzipOk && body.size > 1024 && compressible.any { contentType.startsWith(it) }) {
        val bos = ByteArrayOutputStream(body.size / 3)
        GZIPOutputStream(bos).use { it.write(body) }
        out = bos.toByteArray()
        ex.responseHeaders.set("Content-Encoding", "gzip")
        ex.responseHeaders.add("Vary", "Accept-Encoding")
    }
    ex.sendResponseHeaders(status, if (out.isEmpty()) -1 else out.size.toLong())
    if (out.isNotEmpty()) ex.responseBody.use { it.write(out) }
}
