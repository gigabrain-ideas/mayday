package mayday.contract

import mayday.MaydayApplication
import mayday.MissingSecretKey
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Shared deployments refuse to start with the public development key. A laptop with no database and no single sign-on starts. */
class StartupContractTest {
    private fun start(vararg props: String) = SpringApplicationBuilder(MaydayApplication::class.java).run(
        *(listOf("server.port=0", "server.address=127.0.0.1", "spring.grpc.server.port=0", "spring.grpc.server.address=127.0.0.1",
            "mayday.data-dir=none", "mayday.audit.sinks=none") + props).map { "--$it" }.toTypedArray(),
    )

    private fun assertRefused(vararg props: String) {
        val e = assertFails { start(*props).close() }
        assertTrue(generateSequence(e) { it.cause }.any { it is MissingSecretKey }, e.toString())
    }

    @Test
    fun `single sign-on without a secret key does not start`() =
        assertRefused("spring.grpc.server.inprocess.name=startup-oidc", "mayday.oidc.issuer=https://idp.invalid/realms/x", "mayday.secret-key=")

    @Test
    fun `a database without a secret key does not start`() =
        assertRefused("spring.grpc.server.inprocess.name=startup-db", "mayday.database.url=postgresql://127.0.0.1:1/mayday", "mayday.secret-key=")

    @Test
    fun `a laptop with no database and no single sign-on starts with the development key`() {
        start("spring.grpc.server.inprocess.name=startup-laptop", "mayday.secret-key=").close()
    }
}
