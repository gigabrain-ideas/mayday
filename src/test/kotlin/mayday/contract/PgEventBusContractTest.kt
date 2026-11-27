package mayday.contract

import mayday.adapters.postgres.PgEventBus
import mayday.adapters.postgres.Pool
import mayday.eventually
import mayday.shared.DomainEvent
import mayday.shared.EventBus
import mayday.suspendTest
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test

/** Domain events reach every replica. A replica that lost its LISTEN connection is told to drop what it cached. */
class PgEventBusContractTest {
    private val db = TestDatabase.instance
    private val pools = listOf(pool(), pool())
    private val buses = pools.map { PgEventBus(it) }

    private fun pool() = Pool.of(db.jdbc, db.user, db.password, 2)

    @AfterTest
    fun close() { buses.forEach { it.close() }; pools.forEach { it.close() } }

    private fun record(bus: EventBus) = CopyOnWriteArrayList<DomainEvent>().also { got -> bus.subscribe { got += it } }

    @Test
    fun `an event published on one replica reaches the other`() = suspendTest {
        val got = record(buses[1])
        eventually { publish("setting.changed"); got.any { it.type == "setting.changed" } }
    }

    @Test
    fun `a replica that lost its listen connection gets a resync event and later events`() = suspendTest {
        val got = record(buses[1])
        eventually { publish("probe"); got.any { it.type == "probe" } }
        got.clear()
        pools[0].withConnection { c ->
            c.createStatement().use { it.execute("select pg_terminate_backend(pid) from pg_stat_activity where query = 'listen mayday_events'") }
        }
        eventually(10_000, "no resync after the connection loss") { got.any { it.type == EventBus.RESYNC } }
        eventually { publish("after"); got.any { it.type == "after" } }
    }

    private suspend fun publish(type: String) = buses[0].publish(DomainEvent(type, "s", Instant.now()))
}
