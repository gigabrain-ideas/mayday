package mayday.adapters.postgres

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mayday.shared.DomainEvent
import mayday.shared.EventBus
import mayday.shared.Ids
import mayday.shared.InProcessEventBus
import mayday.shared.JSON
import org.postgresql.PGConnection
import java.util.logging.Logger

/** Fans out domain events to all replicas with LISTEN/NOTIFY, so live updates work behind a load balancer. */
class PgEventBus(private val pool: Pool) : EventBus, AutoCloseable {
    private val log = Logger.getLogger("mayday.events")
    private val local = InProcessEventBus()
    private val origin = Ids.new()
    @Volatile private var running = true
    private val thread = Thread.ofVirtual().name("pg-listen").start { listen() }

    override suspend fun publish(event: DomainEvent) {
        local.publish(event)
        val payload = JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
            put("o", origin); put("e", JSON.encodeToJsonElement(DomainEvent.serializer(), event))
        })
        if (payload.length > 7900) return
        runCatching { pool.withConnection { c -> c.prepareStatement("select pg_notify('mayday_events', ?)").use { it.setString(1, payload); it.execute() } } }
            .onFailure { log.warning("notify failed: ${it.message}") }
    }

    override fun subscribe(listener: (DomainEvent) -> Unit) = local.subscribe(listener)

    private fun listen() {
        while (running) {
            try {
                pool.open().use { c ->
                    c.createStatement().use { it.execute("listen mayday_events") }
                    val pg = c.unwrap(PGConnection::class.java)
                    while (running) {
                        pg.getNotifications(10_000)?.forEach { n ->
                            val o = runCatching { JSON.parseToJsonElement(n.parameter).jsonObject }.getOrNull() ?: return@forEach
                            if (o["o"]?.jsonPrimitive?.content == origin) return@forEach
                            local.deliver(JSON.decodeFromJsonElement(DomainEvent.serializer(), o["e"]!!))
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) { log.warning("listen connection lost: ${e.message}"); Thread.sleep(2000) }
            }
        }
    }

    override fun close() { running = false; thread.interrupt() }
}
