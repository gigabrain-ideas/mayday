package mayday.contract

import com.google.protobuf.Empty
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.api.v1.AuthServiceGrpcKt
import mayday.identity.TeamRole
import mayday.shared.JSON
import mayday.suspendTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Sign-in group claims against a real OpenID Connect provider (Keycloak in a container). The same code path serves Entra ID, Okta,
 * Auth0 and Google: only MAYDAY_OIDC_GROUPS_CLAIM changes. Set MAYDAY_TEST_KEYCLOAK_IMAGE to use another image.
 */
@TestPropertySource(properties = [
    "spring.grpc.server.inprocess.name=oidc-group-sync",
    "mayday.oidc.client-id=mayday",
    "mayday.oidc.client-secret=mayday-secret",
    "mayday.groups.auto-create=true",
    "mayday.groups.filter=^oncall-",
    "mayday.groups.owner-suffix=-owners",
])
class OidcGroupSyncContractTest : ServerContract() {
    companion object {
        private val keycloak: GenericContainer<*> by lazy {
            GenericContainer(System.getenv("MAYDAY_TEST_KEYCLOAK_IMAGE") ?: "quay.io/keycloak/keycloak:26.0.7")
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/mayday-realm.json"), "/opt/keycloak/data/import/mayday-realm.json")
                .withCommand("start-dev", "--import-realm")
                .waitingFor(Wait.forHttp("/realms/mayday/.well-known/openid-configuration").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))
                .apply { start() }
        }
        private val root get() = "http://${keycloak.host}:${keycloak.getMappedPort(8080)}"

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            registry.add("mayday.oidc.issuer") { "$root/realms/mayday" }
        }
    }

    private val client = HttpClient.newHttpClient()
    private val authn by lazy { AuthServiceGrpcKt.AuthServiceCoroutineStub(channel) }

    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, Charsets.UTF_8)}" }

    private fun post(url: String, body: String, bearer: String? = null): String {
        val b = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { b.header("Authorization", "Bearer $it") }
        val r = client.send(b.build(), HttpResponse.BodyHandlers.ofString())
        check(r.statusCode() in 200..299) { "POST $url answered ${r.statusCode()}: ${r.body()}" }
        return r.body()
    }

    private fun accessToken(user: String, password: String): String = JSON.parseToJsonElement(post("$root/realms/mayday/protocol/openid-connect/token",
        form("grant_type" to "password", "client_id" to "mayday", "client_secret" to "mayday-secret", "username" to user, "password" to password, "scope" to "openid")),
    ).jsonObject["access_token"]!!.jsonPrimitive.content

    /** Removes a user from a group with the Keycloak admin API, as an identity administrator would. */
    private fun leaveGroup(username: String, group: String) {
        val admin = JSON.parseToJsonElement(post("$root/realms/master/protocol/openid-connect/token",
            form("grant_type" to "password", "client_id" to "admin-cli", "username" to "admin", "password" to "admin"))).jsonObject["access_token"]!!.jsonPrimitive.content
        fun get(path: String) = JSON.parseToJsonElement(client.send(HttpRequest.newBuilder(URI("$root/admin/realms/mayday$path"))
            .header("Authorization", "Bearer $admin").build(), HttpResponse.BodyHandlers.ofString()).body()).jsonArray
        val userId = get("/users?username=$username&exact=true").single().jsonObject["id"]!!.jsonPrimitive.content
        val groupId = get("/groups?search=$group&exact=true").single { it.jsonObject["name"]!!.jsonPrimitive.content == group }.jsonObject["id"]!!.jsonPrimitive.content
        val r = client.send(HttpRequest.newBuilder(URI("$root/admin/realms/mayday/users/$userId/groups/$groupId")).header("Authorization", "Bearer $admin").DELETE().build(),
            HttpResponse.BodyHandlers.ofString())
        check(r.statusCode() == 204) { "leave group answered ${r.statusCode()}" }
    }

    @Test
    fun `groups in the access token create teams, give roles and follow later changes`() = suspendTest {
        val anaMe = authn.getMe(Empty.getDefaultInstance(), bearer(accessToken("ana", "ana-password")))
        val team = app.directory.teams().single { "oncall-payments" in it.memberGroups }
        assertEquals(setOf("oncall-payments-owners"), team.ownerGroups)
        assertEquals(null, app.directory.teams().firstOrNull { "staff" in it.groups }, "groups outside the filter create no team")
        val ana = app.ports.users.byEmail("ana@example.com")!!
        assertEquals("ana@example.com", anaMe.user.email)
        assertEquals(TeamRole.ADMIN, ana.teams[team.id])

        authn.getMe(Empty.getDefaultInstance(), bearer(accessToken("ben", "ben-password")))
        assertEquals(TeamRole.MEMBER, app.ports.users.byEmail("ben@example.com")!!.teams[team.id])

        leaveGroup("ana", "oncall-payments-owners")
        authn.getMe(Empty.getDefaultInstance(), bearer(accessToken("ana", "ana-password")))
        assertEquals(TeamRole.MEMBER, app.ports.users.byEmail("ana@example.com")!!.teams[team.id])

        leaveGroup("ben", "oncall-payments")
        authn.getMe(Empty.getDefaultInstance(), bearer(accessToken("ben", "ben-password")))
        assertNull(app.ports.users.byEmail("ben@example.com")!!.teams[team.id])
    }

    @Test
    fun `an unverified email cannot take over an account that Mayday already has`() = suspendTest {
        assertStatus(io.grpc.Status.Code.PERMISSION_DENIED, "forbidden") { authn.getMe(Empty.getDefaultInstance(), bearer(accessToken("eve", "eve-password"))) }
        assertNull(app.ports.users.byEmail("member@example.com")!!.externalId, "the account stays unlinked")
    }

    @Test
    fun `a verified email links an account that was created before the first sign-in`() = suspendTest {
        assertNull(userOf("lead").externalId)
        val me = authn.getMe(Empty.getDefaultInstance(), bearer(accessToken("lead", "lead-password")))
        assertEquals(userOf("lead").id, me.user.id)
        assertEquals(TeamRole.ADMIN, app.ports.users.byEmail("lead@example.com")!!.teams[team.id], "the linked account keeps its teams")
    }
}
