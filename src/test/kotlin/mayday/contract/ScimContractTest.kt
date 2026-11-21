package mayday.contract

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.identity.TeamRole
import mayday.identity.TokenScope
import mayday.suspendTest
import org.springframework.test.context.TestPropertySource
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCIM 2.0 contract with the request shapes that Microsoft Entra ID and Okta send. */
@TestPropertySource(properties = ["spring.grpc.server.inprocess.name=scim-contract", "mayday.groups.auto-create=true"])
class ScimContractTest : ServerContract() {
    private val scimToken by lazy { runBlocking { app.identity.issueToken(principal("admin"), "scim", setOf(TokenScope.SCIM), null).secret } }
    private fun headers(token: String = scimToken) = mapOf("Authorization" to "Bearer $token")
    private fun scim(method: String, path: String, body: String? = null, token: String = scimToken) =
        send(method, "/scim/v2$path", body, headers(token), "application/scim+json")
    private fun obj(r: java.net.http.HttpResponse<String>) = json(r).jsonObject
    private fun q(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun unique() = UUID.randomUUID().toString()

    private fun entraUser(email: String, externalId: String) = """{
        "schemas":["urn:ietf:params:scim:schemas:core:2.0:User","urn:ietf:params:scim:schemas:extension:enterprise:2.0:User"],
        "externalId":"$externalId","userName":"$email","active":true,
        "emails":[{"primary":true,"type":"work","value":"$email"}],
        "meta":{"resourceType":"User"},
        "name":{"formatted":"Ana Responder","familyName":"Responder","givenName":"Ana"},
        "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User":{"department":"Payments"},
        "roles":[]}"""

    private fun createUser(email: String = "u-${unique().take(8)}@contoso.com"): String {
        val r = scim("POST", "/Users", entraUser(email, unique()))
        assertEquals(201, r.statusCode(), r.body())
        return obj(r)["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `discovery documents are available`() {
        val cfg = scim("GET", "/ServiceProviderConfig")
        assertEquals(200, cfg.statusCode())
        assertTrue(cfg.headers().firstValue("Content-Type").get().startsWith("application/scim+json"))
        assertEquals("true", obj(cfg)["patch"]!!.jsonObject["supported"]!!.jsonPrimitive.content)
        assertEquals(2, obj(scim("GET", "/ResourceTypes"))["totalResults"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `only an administrator token with the SCIM scope has access`() {
        assertEquals(401, send("GET", "/scim/v2/Users").statusCode())
        assertEquals(401, send("GET", "/scim/v2/Users", headers = mapOf("Cookie" to login("admin@example.com"))).statusCode())
        val lead = runBlocking { app.identity.issueToken(principal("lead"), "scim", setOf(TokenScope.SCIM), null).secret }
        val r = scim("GET", "/Users", token = lead)
        assertEquals(403, r.statusCode())
        assertEquals("urn:ietf:params:scim:api:messages:2.0:Error", obj(r)["schemas"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(403, scim("GET", "/Users", token = token("member")).statusCode())
    }

    @Test
    fun `Entra creates a user after a filter miss and a duplicate is a uniqueness error`() {
        val email = "ana-${unique().take(8)}@contoso.com"
        val miss = obj(scim("GET", "/Users?filter=" + q("userName eq \"$email\"")))
        assertEquals(0, miss["totalResults"]!!.jsonPrimitive.content.toInt())
        val ext = unique()
        val created = scim("POST", "/Users", entraUser(email, ext))
        assertEquals(201, created.statusCode(), created.body())
        val u = obj(created)
        assertEquals(email, u["userName"]!!.jsonPrimitive.content)
        assertEquals("Ana Responder", u["displayName"]!!.jsonPrimitive.content)
        assertEquals("true", u["active"]!!.jsonPrimitive.content)
        val hit = obj(scim("GET", "/Users?filter=" + q("externalId eq \"$ext\"")))
        assertEquals(u["id"], hit["Resources"]!!.jsonArray.single().jsonObject["id"])
        val dup = scim("POST", "/Users", entraUser(email, unique()))
        assertEquals(409, dup.statusCode())
        assertEquals("uniqueness", obj(dup)["scimType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PATCH accepts Entra string booleans, Okta value objects and email paths`() {
        val id = createUser()
        val off = scim("PATCH", "/Users/$id", """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[{"op":"Replace","path":"active","value":"False"}]}""")
        assertEquals(200, off.statusCode(), off.body())
        assertEquals("false", obj(off)["active"]!!.jsonPrimitive.content)
        assertTrue(runBlocking { app.ports.users.get(id)!!.disabled })
        val on = scim("PATCH", "/Users/$id", """{"Operations":[{"op":"replace","value":{"active":true,"displayName":"Ana R."}}]}""")
        assertEquals("true", obj(on)["active"]!!.jsonPrimitive.content)
        assertEquals("Ana R.", obj(on)["displayName"]!!.jsonPrimitive.content)
        val newEmail = "moved-${unique().take(8)}@contoso.com"
        val mail = scim("PATCH", "/Users/$id", """{"Operations":[
            {"op":"Replace","path":"emails[type eq \"work\"].value","value":"$newEmail"},
            {"op":"Replace","path":"userName","value":"$newEmail"},
            {"op":"Add","path":"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager","value":"x"}]}""")
        assertEquals(200, mail.statusCode(), mail.body())
        assertEquals(newEmail, obj(mail)["userName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `groups become teams and SCIM membership drives team membership`() = suspendTest {
        val ana = createUser()
        val ben = createUser()
        val ext = unique()
        val name = "Payments On-call ${unique().take(6)}"
        val g = scim("POST", "/Groups", """{"schemas":["urn:ietf:params:scim:schemas:core:2.0:Group"],"externalId":"$ext","displayName":"$name","meta":{"resourceType":"Group"}}""")
        assertEquals(201, g.statusCode(), g.body())
        val gid = obj(g)["id"]!!.jsonPrimitive.content
        val team = app.directory.teams().single { ext in it.memberGroups }
        assertEquals(name, team.name)

        val add = scim("PATCH", "/Groups/$gid", """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[{"op":"Add","path":"members","value":[{"value":"$ana"},{"value":"$ben"}]}]}""")
        assertEquals(200, add.statusCode(), add.body())
        assertEquals(TeamRole.MEMBER, app.ports.users.get(ana)!!.teams[team.id])
        assertEquals(2, (obj(add)["members"] as JsonArray).size)

        val lean = obj(scim("GET", "/Groups?filter=" + q("displayName eq \"$name\"") + "&excludedAttributes=members"))
        val res = lean["Resources"]!!.jsonArray.single().jsonObject
        assertEquals(gid, res["id"]!!.jsonPrimitive.content)
        assertFalse(res.containsKey("members"))

        scim("PATCH", "/Groups/$gid", """{"Operations":[{"op":"Remove","path":"members[value eq \"$ana\"]"}]}""")
        assertNull(app.ports.users.get(ana)!!.teams[team.id])
        assertEquals(TeamRole.MEMBER, app.ports.users.get(ben)!!.teams[team.id])

        val renamed = scim("PATCH", "/Groups/$gid", """{"Operations":[{"op":"Replace","path":"displayName","value":"$name renamed"}]}""")
        assertEquals("$name renamed", obj(renamed)["displayName"]!!.jsonPrimitive.content)

        assertEquals(204, scim("DELETE", "/Groups/$gid").statusCode())
        assertNull(app.ports.users.get(ben)!!.teams[team.id])
        assertEquals(404, scim("GET", "/Groups/$gid").statusCode())
    }

    @Test
    fun `PUT replaces a group with its members`() = suspendTest {
        val ana = createUser()
        val g = obj(scim("POST", "/Groups", """{"displayName":"SRE ${unique().take(6)}","members":[{"value":"$ana"}]}"""))
        val gid = g["id"]!!.jsonPrimitive.content
        val put = scim("PUT", "/Groups/$gid", """{"displayName":"${g["displayName"]!!.jsonPrimitive.content}","members":[]}""")
        assertEquals(200, put.statusCode(), put.body())
        assertEquals(0, (obj(put)["members"] as JsonArray).size)
    }

    @Test
    fun `deleting a user disables them and unsupported filters are rejected`() = suspendTest {
        val id = createUser()
        assertEquals(204, scim("DELETE", "/Users/$id").statusCode())
        assertTrue(app.ports.users.get(id)!!.disabled)
        val bad = scim("GET", "/Users?filter=" + q("name.familyName co \"x\""))
        assertEquals(400, bad.statusCode())
        assertEquals("invalidFilter", obj(bad)["scimType"]!!.jsonPrimitive.content)
        assertEquals(404, scim("GET", "/Users/missing").statusCode())
        val page = obj(scim("GET", "/Users?startIndex=1&count=2"))
        assertTrue((page["Resources"] as JsonArray).size <= 2)
        assertTrue(page["totalResults"]!!.jsonPrimitive.content.toInt() >= 2)
        assertTrue(page["Resources"]!!.jsonArray.all { (it as JsonObject).containsKey("userName") })
    }
}
