package mayday.contract

import mayday.Ports
import mayday.suspendTest
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.Pool
import mayday.adapters.postgres.PostgresStore
import mayday.shared.Ids
import mayday.shared.InProcessEventBus
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryStoreContractTest : StoreContract() {
    override fun newPorts() = Ports.memory(MemoryStore(null))
}

class JournaledMemoryStoreContractTest : StoreContract() {
    override fun newPorts() = Ports.memory(MemoryStore(Files.createTempDirectory("mayday")))

    @Test
    fun `data survives a restart`() = suspendTest {
        val dir = Files.createTempDirectory("mayday")
        val first = MemoryStore(dir)
        val n1 = first.incidents.nextNumber()
        first.teams.save(mayday.directory.Team(Ids.new(), "x", "X", createdAt = java.time.Instant.now()))
        first.close()
        val second = MemoryStore(dir)
        assertEquals("X", second.teams.bySlug("x")!!.name)
        assertEquals(n1 + 1, second.incidents.nextNumber())
        second.close()
    }
}

/** Runs only when MAYDAY_TEST_DATABASE_URL points to a disposable PostgreSQL database. */
class PostgresStoreContractTest : StoreContract() {
    override fun newPorts(): Ports {
        val url = System.getenv("MAYDAY_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "MAYDAY_TEST_DATABASE_URL is not set")
        val jdbc = if (url!!.startsWith("jdbc:")) url else "jdbc:$url"
        val schema = "t_" + Ids.new().take(12)
        val admin = Pool.of(jdbc, System.getenv("MAYDAY_TEST_DATABASE_USER"), System.getenv("MAYDAY_TEST_DATABASE_PASSWORD"), 1)
        admin.dataSource.connection.use { c -> c.createStatement().use { it.execute("create schema $schema") } }
        admin.close()
        val sep = if (jdbc.contains('?')) "&" else "?"
        val pool = Pool.of("$jdbc${sep}currentSchema=$schema", System.getenv("MAYDAY_TEST_DATABASE_USER"), System.getenv("MAYDAY_TEST_DATABASE_PASSWORD"), 8)
        return Ports.postgres(PostgresStore(pool), InProcessEventBus())
    }
}
