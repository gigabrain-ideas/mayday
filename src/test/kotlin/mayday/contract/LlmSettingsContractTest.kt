package mayday.contract

import kotlinx.coroutines.runBlocking
import mayday.App
import mayday.AppSettings
import mayday.MaydayProperties
import mayday.Ports
import mayday.adapters.memory.MemoryStore
import mayday.agents.AuthStyle
import mayday.agents.ClientAuth
import mayday.agents.CredentialMode
import mayday.agents.LlmEnvironment
import mayday.agents.LlmInput
import mayday.agents.OAuthGrant
import mayday.identity.OrgRole
import mayday.identity.User
import mayday.shared.DomainException
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.suspendTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** LLM settings that the environment sets: they win over stored values, show as managed and cannot change through UpdateLlm. */
class LlmSettingsContractTest {
    private val env = LlmEnvironment(
        provider = "openai",
        model = "gateway-model",
        apiKey = "env-secret",
        credential = CredentialMode.STATIC,
        headers = mapOf("x-env" to "1"),
    )
    private val app = App(AppSettings(secretKey = "test-secret", llm = env), Ports.memory(MemoryStore(null))).also { runBlocking { it.start() } }
    private val admin = User(Ids.new(), "admin@example.com", "admin", OrgRole.ADMIN, emptyMap(), createdAt = Instant.now())
        .also { runBlocking { app.ports.users.save(it) } }
        .let { app.identity.principalForUser(it) }

    @Test
    fun `environment values win and are listed as managed`() = suspendTest {
        val v = app.agents.llm(admin)
        assertEquals("openai", v.provider)
        assertEquals("gateway-model", v.model)
        assertEquals(AuthStyle.BEARER, v.authStyle, "the auth style follows the provider")
        assertTrue(v.hasApiKey)
        assertEquals(mapOf("x-env" to "1"), v.headers)
        assertEquals(setOf("provider", "model", "apiKey", "credential", "headers"), v.managedFields.toSet())
        assertTrue(!v.toString().contains("env-secret"))
    }

    @Test
    fun `changing a managed field is invalid`() = suspendTest {
        for (input in listOf(LlmInput(model = "other"), LlmInput(apiKey = "x"), LlmInput(provider = "anthropic"), LlmInput(headers = emptyMap()),
            LlmInput(credential = CredentialMode.NONE))) {
            val e = assertFailsWith<DomainException> { app.agents.updateLlm(admin, input) }
            assertEquals("invalid", e.code)
            assertTrue(e.message!!.contains("set by the environment"), e.message)
        }
    }

    @Test
    fun `fields that the environment does not set stay editable`() = suspendTest {
        val v = app.agents.updateLlm(admin, LlmInput(effort = "low", tokenParams = mapOf("audience" to "gw"), enabled = true))
        assertEquals("low", v.effort)
        assertEquals(mapOf("audience" to "gw"), v.tokenParams)
        assertEquals("gateway-model", v.model)
        assertTrue(v.ready)
    }

    @Test
    fun `generic OAuth 2 variables fill the settings and no provider-specific variable is read`() {
        val e = MaydayProperties.Llm(
            credential = "oauth2", issuer = "https://idp.example.com/realms/acme", grant = "token-exchange", clientAuth = "post",
            assertionFile = "/var/run/secrets/mayday/workload-identity/token", scope = "gateway.invoke",
            tokenParams = "audience=https://gateway.example.com, resource=https://gateway.example.com/",
        ).environment()
        assertEquals(CredentialMode.OAUTH2, e.credential)
        assertEquals("https://idp.example.com/realms/acme", e.issuer)
        assertEquals(OAuthGrant.TOKEN_EXCHANGE, e.grant)
        assertEquals(ClientAuth.POST, e.clientAuth)
        assertEquals(mapOf("audience" to "https://gateway.example.com", "resource" to "https://gateway.example.com/"), e.tokenParams)
        assertNull(e.tokenUrl)
        assertTrue(listOf("issuer", "grant", "clientAuth", "tokenParams").all { it in e.managedFields })
        assertNull(MaydayProperties.Llm(credential = "oauth2").environment().clientId, "no fallback to platform variables")
        assertEquals(mapOf("x-a" to "1", "x-b" to "two=2"), MaydayProperties.parsePairs("x-a=1, x-b=two=2", "X"))
    }

    @Test
    fun `invalid OAuth 2 variables fail at startup`() {
        assertFailsWith<IllegalArgumentException> { MaydayProperties.Llm(grant = "password").environment() }
        assertFailsWith<IllegalArgumentException> { MaydayProperties.Llm(clientAuth = "jwt").environment() }
        assertFailsWith<IllegalArgumentException> { MaydayProperties.Llm(tokenParams = "client_secret=x").environment() }
    }

    @Test
    fun `token parameters and issuer are validated and stored`() = suspendTest {
        assertFailsWith<Invalid> { app.agents.updateLlm(admin, LlmInput(tokenParams = mapOf("scope" to "x"))) }
        assertFailsWith<Invalid> { app.agents.updateLlm(admin, LlmInput(issuer = "not a url")) }
        val v = app.agents.updateLlm(admin, LlmInput(tokenParams = mapOf("audience" to "gw"), clientAuth = ClientAuth.POST))
        assertEquals(mapOf("audience" to "gw"), v.tokenParams)
        assertEquals(ClientAuth.POST, v.clientAuth)
    }
}
