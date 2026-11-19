package mayday.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

typealias Ts = @Serializable(InstantSerializer::class) Instant

val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private val random = SecureRandom()
private val b64 = Base64.getUrlEncoder().withoutPadding()

object Ids {
    fun new(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val ts = System.currentTimeMillis()
        for (i in 0 until 6) bytes[i] = (ts shr (40 - 8 * i)).toByte()
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun secret(bytes: Int = 32): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b64.encodeToString(b)
    }
}

fun interface Clock {
    fun now(): Instant

    companion object {
        val system = Clock { Instant.now() }
    }
}

@Serializable
enum class Severity(val rank: Int) {
    SEV1(1), SEV2(2), SEV3(3), SEV4(4), SEV5(5);

    fun atLeast(min: Severity) = rank <= min.rank
}

@Serializable
enum class ActorKind { USER, AGENT, SYSTEM }

@Serializable
data class Actor(
    val kind: ActorKind,
    val id: String,
    val name: String,
    val onBehalfOf: String? = null,
) {
    companion object {
        val system = Actor(ActorKind.SYSTEM, "system", "Mayday")
    }
}

open class DomainException(val code: String, message: String) : RuntimeException(message)
class NotFound(what: String) : DomainException("not_found", "$what not found")
class Forbidden(message: String = "You do not have permission for this action") : DomainException("forbidden", message)
class Invalid(message: String) : DomainException("invalid", message)
class Conflict(message: String) : DomainException("conflict", message)
class Unauthenticated(message: String = "Authentication required") : DomainException("unauthenticated", message)
class Unavailable(message: String) : DomainException("unavailable", message)

@Serializable
data class Page<T>(val items: List<T>, val nextCursor: String? = null)

@Serializable
data class DomainEvent(
    val type: String,
    val subjectId: String,
    val at: Ts,
    val teamIds: List<String> = emptyList(),
    val data: Map<String, String> = emptyMap(),
)

fun interface EventPublisher {
    fun publish(event: DomainEvent)
}

interface EventBus : EventPublisher {
    fun subscribe(listener: (DomainEvent) -> Unit): AutoCloseable
}

class InProcessEventBus : EventBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(DomainEvent) -> Unit>()

    override fun publish(event: DomainEvent) {
        for (l in listeners) runCatching { l(event) }
    }

    override fun subscribe(listener: (DomainEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
