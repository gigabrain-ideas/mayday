package mayday.contract

import mayday.Ports
import mayday.suspendTest
import mayday.adapters.memory.MemoryStore
import mayday.adapters.postgres.Pool
import mayday.adapters.postgres.PostgresStore
import mayday.shared.Ids
import mayday.shared.InProcessEventBus
import org.testcontainers.postgresql.PostgreSQLContainer
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

/** Uses MAYDAY_TEST_DATABASE_URL when set, else one disposable PostgreSQL container per test run. Each test gets its own schema. */
class PostgresStoreContractTest : StoreContract() {
    override fun newPorts(): Ports {
        val db = TestDatabase.instance
        val schema = "t_" + Ids.new().take(12)
        Pool.of(db.jdbc, db.user, db.password, 1).use { admin ->
            admin.dataSource.connection.use { c -> c.createStatement().use { it.execute("create schema $schema") } }
        }
        val sep = if (db.jdbc.contains('?')) "&" else "?"
        return Ports.postgres(PostgresStore(Pool.of("${db.jdbc}${sep}currentSchema=$schema", db.user, db.password, 8)), InProcessEventBus())
    }
}

class TestDatabase private constructor(val jdbc: String, val user: String?, val password: String?) {
    /** A fresh container can refuse the first connections for a moment after it reports ready. */
    private fun awaitConnection() {
        repeat(50) {
            if (runCatching { java.sql.DriverManager.getConnection(jdbc, user, password).close() }.isSuccess) return
            Thread.sleep(200)
        }
    }

    companion object {
        val instance: TestDatabase by lazy {
            val url = System.getenv("MAYDAY_TEST_DATABASE_URL")
            if (!url.isNullOrBlank()) {
                TestDatabase(if (url.startsWith("jdbc:")) url else "jdbc:$url",
                    System.getenv("MAYDAY_TEST_DATABASE_USER"), System.getenv("MAYDAY_TEST_DATABASE_PASSWORD"))
            } else {
                val c = PostgreSQLContainer(System.getenv("MAYDAY_TEST_POSTGRES_IMAGE") ?: "postgres:18-alpine").apply { start() }
                TestDatabase(c.jdbcUrl, c.username, c.password).also { it.awaitConnection() }
            }
        }
    }
}
