package mayday.contract

import com.google.protobuf.Empty
import com.google.protobuf.Message
import mayday.api.v1.Event
import mayday.api.v1.IncidentRef
import mayday.api.v1.Me
import mayday.api.v1.SubscribeRequest
import org.springframework.test.context.TestPropertySource
import java.io.DataInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** gRPC-Web bridge on the HTTP port: framing, trailers, routing and incremental streaming. */
@TestPropertySource(properties = ["spring.grpc.server.inprocess.name=grpc-web-contract"])
class GrpcWebContractTest : ServerContract() {
    private class Frame(val flag: Int, val payload: ByteArray) {
        val trailers: Map<String, String>
            get() = payload.toString(Charsets.US_ASCII).split("\r\n").filter { it.isNotBlank() }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    }

    private fun frame(m: Message): ByteArray {
        val bytes = m.toByteArray()
        return ByteBuffer.allocate(5 + bytes.size).put(0).putInt(bytes.size).put(bytes).array()
    }

    private fun readFrame(input: InputStream): Frame? {
        val d = DataInputStream(input)
        val flag = d.read().takeIf { it >= 0 } ?: return null
        val payload = ByteArray(d.readInt()).also { d.readFully(it) }
        return Frame(flag, payload)
    }

    private fun frames(body: ByteArray): List<Frame> = body.inputStream().let { s -> generateSequence { readFrame(s) }.toList() }

    private fun request(path: String, body: ByteArray, cookie: String? = null, contentType: String = "application/grpc-web+proto") =
        HttpRequest.newBuilder(URI(base + path)).header("Content-Type", contentType).header("X-Grpc-Web", "1")
            .apply { cookie?.let { header("Cookie", it) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()

    private fun call(path: String, m: Message, cookie: String? = null, contentType: String = "application/grpc-web+proto") =
        http.send(request(path, frame(m), cookie, contentType), HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `a successful call returns a message frame and an OK trailer frame`() {
        val r = call("/mayday.v1.AuthService/GetMe", Empty.getDefaultInstance(), login("member@example.com"))
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").get().startsWith("application/grpc-web+proto"))
        val (message, trailer) = frames(r.body()).also { assertEquals(2, it.size) }
        assertEquals(0, message.flag)
        assertEquals("member@example.com", Me.parseFrom(message.payload).user.email)
        assertEquals(0x80, trailer.flag)
        assertEquals("0", trailer.trailers["grpc-status"])
    }

    @Test
    fun `the plain grpc-web content type is routed too`() {
        val r = call("/mayday.v1.AuthService/GetAuthConfig", Empty.getDefaultInstance(), contentType = "application/grpc-web")
        assertEquals(200, r.statusCode())
        assertEquals("0", frames(r.body()).last().trailers["grpc-status"])
    }

    @Test
    fun `errors are a trailer frame with status, message and domain code`() {
        val unauth = frames(call("/mayday.v1.AuthService/GetMe", Empty.getDefaultInstance()).body()).single()
        assertEquals(0x80, unauth.flag)
        assertEquals("16", unauth.trailers["grpc-status"])
        assertEquals("unauthenticated", unauth.trailers["mayday-error-code"])

        val missing = call("/mayday.v1.IncidentService/GetIncident", IncidentRef.newBuilder().setRef("INC-424242").build(), login("viewer@example.com"))
        assertEquals(200, missing.statusCode())
        val t = frames(missing.body()).single().trailers
        assertEquals("5", t["grpc-status"])
        assertEquals("not_found", t["mayday-error-code"])
        assertEquals("Incident not found", URLDecoder.decode(t["grpc-message"], Charsets.UTF_8))
    }

    @Test
    fun `unknown methods are 404 and malformed frames are 400`() {
        assertEquals(404, call("/mayday.v1.AuthService/Nope", Empty.getDefaultInstance()).statusCode())
        assertEquals(404, call("/mayday.v1.NoService/GetMe", Empty.getDefaultInstance()).statusCode())
        val short = http.send(request("/mayday.v1.AuthService/GetAuthConfig", byteArrayOf(0, 0)), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(400, short.statusCode())
        val lying = http.send(request("/mayday.v1.AuthService/GetAuthConfig", byteArrayOf(0, 0, 0, 0, 9)), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(400, lying.statusCode())
    }

    @Test
    fun `other content types are not routed to the bridge`() {
        val r = call("/mayday.v1.AuthService/GetAuthConfig", Empty.getDefaultInstance(), contentType = "application/json")
        assertFalse(r.headers().firstValue("Content-Type").orElse("").startsWith("application/grpc-web"))
        assertTrue(r.statusCode() >= 400, "status ${r.statusCode()}")
    }

    @Test
    fun `server streaming delivers frames before the stream ends`() {
        val cookie = login("viewer@example.com")
        val resp = http.send(request("/mayday.v1.EventService/Subscribe", frame(SubscribeRequest.getDefaultInstance()), cookie), HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(200, resp.statusCode())
        resp.body().use { input ->
            val first = CompletableFuture.supplyAsync { readFrame(input) }.get(5, TimeUnit.SECONDS)!!
            assertEquals(0, first.flag)
            assertEquals("ping", Event.parseFrom(first.payload).type)
        }
    }
}
