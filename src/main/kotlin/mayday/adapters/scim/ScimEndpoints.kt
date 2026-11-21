package mayday.adapters.scim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mayday.App
import mayday.MaydayProperties
import mayday.adapters.auth.Authenticator
import mayday.identity.Principal
import mayday.identity.User
import mayday.provisioning.DirectoryGroup
import mayday.provisioning.ProvisionedUser
import mayday.shared.DomainException
import mayday.shared.Invalid
import mayday.shared.JSON
import mayday.shared.Unauthenticated
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private const val USER = "urn:ietf:params:scim:schemas:core:2.0:User"
private const val GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group"
private const val LIST = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
private const val ERROR = "urn:ietf:params:scim:api:messages:2.0:Error"
private val SCIM_JSON = MediaType.parseMediaType("application/scim+json")

/** A filter that this server cannot evaluate. SCIM answers it with scimType "invalidFilter". */
private class InvalidFilter(message: String) : DomainException("invalid", message)

private val FILTER = Regex("""^\s*([A-Za-z.]+)\s+eq\s+"((?:[^"\\]|\\.)*)"\s*$""", RegexOption.IGNORE_CASE)
private val MEMBER_PATH = Regex("""^members\[\s*value\s+eq\s+"([^"]+)"\s*]$""", RegexOption.IGNORE_CASE)
private val USER_FILTERS = setOf("username", "externalid", "id", "emails", "emails.value")
private val GROUP_FILTERS = setOf("displayname", "externalid", "id")

/**
 * SCIM 2.0 service provider (RFC 7643, RFC 7644) for Users and Groups. Any SCIM client works: Microsoft Entra ID, Okta, OneLogin,
 * JumpCloud, Keycloak plugins. Only a bearer token with the SCIM scope of an administrator has access; session cookies do not.
 */
@RestController
@RequestMapping("/scim/v2")
class ScimEndpoints(private val app: App, private val auth: Authenticator, props: MaydayProperties) {
    private val base = props.baseUrl + "/scim/v2"
    private val svc get() = app.provisioning

    // ---- discovery ----

    @GetMapping("/ServiceProviderConfig")
    suspend fun config(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?) = scim(a) { _ ->
        ok(buildJsonObject {
            putJsonArray("schemas") { add(JsonPrimitive("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig")) }
            putJsonObject("patch") { put("supported", true) }
            putJsonObject("bulk") { put("supported", false); put("maxOperations", 0); put("maxPayloadSize", 0) }
            putJsonObject("filter") { put("supported", true); put("maxResults", 500) }
            putJsonObject("changePassword") { put("supported", false) }
            putJsonObject("sort") { put("supported", false) }
            putJsonObject("etag") { put("supported", false) }
            putJsonArray("authenticationSchemes") {
                add(buildJsonObject { put("type", "oauthbearertoken"); put("name", "Bearer token"); put("description", "A Mayday API token with the SCIM scope") })
            }
        })
    }

    @GetMapping("/ResourceTypes")
    suspend fun resourceTypes(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?) = scim(a) { _ ->
        val types = listOf(Triple("User", "/Users", USER), Triple("Group", "/Groups", GROUP)).map { (name, ep, schema) ->
            buildJsonObject {
                putJsonArray("schemas") { add(JsonPrimitive("urn:ietf:params:scim:schemas:core:2.0:ResourceType")) }
                put("id", name); put("name", name); put("endpoint", ep); put("schema", schema)
            }
        }
        ok(list(types, types.size.toLong(), 1))
    }

    @GetMapping("/Schemas")
    suspend fun schemas(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?) = scim(a) { _ ->
        val items = listOf(USER to "User", GROUP to "Group").map { (id, name) -> buildJsonObject { put("id", id); put("name", name) } }
        ok(list(items, items.size.toLong(), 1))
    }

    // ---- users ----

    @GetMapping("/Users")
    suspend fun users(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?,
        @RequestParam(required = false) filter: String?,
        @RequestParam(required = false) startIndex: Int?,
        @RequestParam(required = false) count: Int?,
    ) = scim(a) { p ->
        val (attr, value) = filter(filter, USER_FILTERS)
        val start = (startIndex ?: 1).coerceAtLeast(1)
        val r = svc.users(p, attr, value, start, (count ?: 100).coerceIn(0, 500))
        ok(list(r.items.map(::user), r.total, start))
    }

    @GetMapping("/Users/{id}")
    suspend fun getUser(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String) =
        scim(a) { p -> ok(user(svc.user(p, id))) }

    @PostMapping("/Users")
    suspend fun createUser(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @RequestBody(required = false) body: String?) =
        scim(a) { p -> ok(user(svc.createUser(p, provisioned(body(body)))), HttpStatus.CREATED) }

    @PutMapping("/Users/{id}")
    suspend fun replaceUser(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String, @RequestBody(required = false) body: String?) =
        scim(a) { p -> ok(user(svc.updateUser(p, id, provisioned(body(body))))) }

    @PatchMapping("/Users/{id}")
    suspend fun patchUser(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String, @RequestBody(required = false) body: String?) =
        scim(a) { p ->
            val doc = user(svc.user(p, id)).toMutableMap()
            for (op in operations(body(body))) applyUserOp(doc, op)
            ok(user(svc.updateUser(p, id, provisioned(JsonObject(doc)))))
        }

    @DeleteMapping("/Users/{id}")
    suspend fun deleteUser(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String) =
        scim(a) { p -> svc.deleteUser(p, id); ResponseEntity.noContent().build<String>() }

    // ---- groups ----

    @GetMapping("/Groups")
    suspend fun groups(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?,
        @RequestParam(required = false) filter: String?,
        @RequestParam(required = false) startIndex: Int?,
        @RequestParam(required = false) count: Int?,
        @RequestParam(required = false) excludedAttributes: String?,
    ) = scim(a) { p ->
        val (attr, value) = filter(filter, GROUP_FILTERS)
        val start = (startIndex ?: 1).coerceAtLeast(1)
        val r = svc.groups(p, attr, value, start, (count ?: 100).coerceIn(0, 500))
        ok(list(r.items.map { group(it, withMembers(excludedAttributes)) }, r.total, start))
    }

    @GetMapping("/Groups/{id}")
    suspend fun getGroup(
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String,
        @RequestParam(required = false) excludedAttributes: String?,
    ) = scim(a) { p -> ok(group(svc.group(p, id), withMembers(excludedAttributes))) }

    @PostMapping("/Groups")
    suspend fun createGroup(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @RequestBody(required = false) body: String?) =
        scim(a) { p ->
            val b = body(body)
            ok(group(svc.createGroup(p, b.str("displayName").orEmpty(), b.str("externalId"), memberIds(b["members"])), true), HttpStatus.CREATED)
        }

    @PutMapping("/Groups/{id}")
    suspend fun replaceGroup(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String, @RequestBody(required = false) body: String?) =
        scim(a) { p ->
            val b = body(body)
            val g = svc.updateGroup(p, id, b.str("displayName").orEmpty(), b.str("externalId"))
            svc.changeMembers(p, g.id, replace = memberIds(b["members"]))
            ok(group(svc.group(p, g.id), true))
        }

    @PatchMapping("/Groups/{id}")
    suspend fun patchGroup(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String, @RequestBody(required = false) body: String?) =
        scim(a) { p ->
            var g = svc.group(p, id)
            for (op in operations(body(body))) {
                val kind = op.str("op")?.lowercase() ?: throw Invalid("Each operation needs 'op'")
                val path = op.str("path")?.trim()
                val value = op["value"]
                val member = path?.let { MEMBER_PATH.matchEntire(it) }?.groupValues?.get(1)
                when {
                    member != null && kind == "remove" -> svc.changeMembers(p, g.id, remove = setOf(member))
                    path.equals("members", true) -> when (kind) {
                        "add" -> svc.changeMembers(p, g.id, add = memberIds(value))
                        "remove" -> if (value == null || value is JsonNull) svc.changeMembers(p, g.id, replace = emptySet()) else svc.changeMembers(p, g.id, remove = memberIds(value))
                        "replace" -> svc.changeMembers(p, g.id, replace = memberIds(value))
                        else -> throw Invalid("Unknown operation '$kind'")
                    }
                    path.equals("displayName", true) -> g = svc.updateGroup(p, g.id, value.text() ?: g.displayName, g.externalId)
                    path.equals("externalId", true) -> g = svc.updateGroup(p, g.id, g.displayName, if (kind == "remove") null else value.text())
                    path == null && value is JsonObject -> {
                        if (value.containsKey("displayName") || value.containsKey("externalId")) {
                            g = svc.updateGroup(p, g.id, value.str("displayName") ?: g.displayName, if (value.containsKey("externalId")) value.str("externalId") else g.externalId)
                        }
                        value["members"]?.let { m -> if (kind == "add") svc.changeMembers(p, g.id, add = memberIds(m)) else svc.changeMembers(p, g.id, replace = memberIds(m)) }
                    }
                    else -> Unit
                }
            }
            ok(group(svc.group(p, g.id), true))
        }

    @DeleteMapping("/Groups/{id}")
    suspend fun deleteGroup(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) a: String?, @PathVariable id: String) =
        scim(a) { p -> svc.deleteGroup(p, id); ResponseEntity.noContent().build<String>() }

    // ---- mapping ----

    private fun user(u: User) = buildJsonObject {
        putJsonArray("schemas") { add(JsonPrimitive(USER)) }
        put("id", u.id)
        u.scimExternalId?.let { put("externalId", it) }
        put("userName", u.email)
        putJsonObject("name") { put("formatted", u.name) }
        put("displayName", u.name)
        putJsonArray("emails") { add(buildJsonObject { put("value", u.email); put("type", "work"); put("primary", true) }) }
        put("active", !u.disabled)
        putJsonObject("meta") { put("resourceType", "User"); put("created", u.createdAt.toString()); put("location", "$base/Users/${u.id}") }
    }

    private suspend fun group(g: DirectoryGroup, withMembers: Boolean): JsonObject {
        val members = if (withMembers) svc.members(g) else null
        return group(g, members)
    }

    private fun group(g: DirectoryGroup, members: List<User>?) = buildJsonObject {
        putJsonArray("schemas") { add(JsonPrimitive(GROUP)) }
        put("id", g.id)
        g.externalId?.let { put("externalId", it) }
        put("displayName", g.displayName)
        if (members != null) putJsonArray("members") {
            members.forEach { u -> add(buildJsonObject { put("value", u.id); put("display", u.name); put("\$ref", "$base/Users/${u.id}") }) }
        }
        putJsonObject("meta") {
            put("resourceType", "Group"); put("created", g.createdAt.toString()); put("lastModified", g.updatedAt.toString()); put("location", "$base/Groups/${g.id}")
        }
    }

    private fun provisioned(b: JsonObject): ProvisionedUser {
        val emails = (b["emails"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val primary = (emails.firstOrNull { it["primary"].bool() == true } ?: emails.firstOrNull())?.str("value")
        val userName = b.str("userName").orEmpty()
        val email = if (userName.contains('@')) userName else primary ?: userName
        val n = b["name"] as? JsonObject
        val name = b.str("displayName") ?: n?.str("formatted") ?: listOfNotNull(n?.str("givenName"), n?.str("familyName")).joinToString(" ")
        return ProvisionedUser(email, name, b["active"].bool() ?: true, b.str("externalId"))
    }

    /** Applies one PATCH operation to the SCIM document of a user. Paths that Mayday does not store (for example enterprise extensions) are ignored. */
    private fun applyUserOp(doc: MutableMap<String, JsonElement>, op: JsonObject) {
        val kind = op.str("op")?.lowercase() ?: throw Invalid("Each operation needs 'op'")
        val path = op.str("path")?.trim()
        val value = op["value"] ?: JsonNull
        if (kind !in setOf("add", "replace", "remove")) throw Invalid("Unknown operation '$kind'")
        if (path == null) {
            if (value is JsonObject) value.forEach { (k, v) -> if (k.contains('.')) setPath(doc, k, v) else doc[k] = v }
            return
        }
        if (path.lowercase().startsWith("emails")) {
            val v = if (path.contains(']')) value.text() else (value as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("value") } ?: value.text()
            if (kind != "remove" && v != null) doc["emails"] = buildJsonArray { add(buildJsonObject { put("value", v); put("primary", true) }) }
            return
        }
        if (kind == "remove") doc.remove(path) else setPath(doc, path, value)
    }

    private fun setPath(doc: MutableMap<String, JsonElement>, path: String, value: JsonElement) {
        val parts = path.split('.')
        val key = doc.keys.firstOrNull { it.equals(parts[0], true) } ?: parts[0]
        if (parts.size == 1) { doc[key] = value; return }
        val inner = (doc[key] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val sub = inner.keys.firstOrNull { it.equals(parts[1], true) } ?: parts[1]
        inner[sub] = value
        if (key == "name") { inner.remove("formatted"); doc.remove("displayName") }
        doc[key] = JsonObject(inner)
    }

    // ---- helpers ----

    private fun list(items: List<JsonElement>, total: Long, start: Int) = buildJsonObject {
        putJsonArray("schemas") { add(JsonPrimitive(LIST)) }
        put("totalResults", total); put("startIndex", start); put("itemsPerPage", items.size)
        put("Resources", JsonArray(items))
    }

    private fun filter(raw: String?, allowed: Set<String>): Pair<String?, String?> {
        if (raw.isNullOrBlank()) return null to null
        val m = FILTER.matchEntire(raw) ?: throw InvalidFilter("Only filters of the form 'attribute eq \"value\"' are supported")
        val attr = m.groupValues[1]
        if (attr.lowercase() !in allowed) throw InvalidFilter("Filtering by '$attr' is not supported")
        return attr to m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun withMembers(excluded: String?) = excluded?.split(',')?.none { it.trim().equals("members", true) } ?: true

    private fun memberIds(v: JsonElement?): Set<String> = when (v) {
        is JsonArray -> v.mapNotNull { (it as? JsonObject)?.str("value") }.toSet()
        is JsonObject -> setOfNotNull(v.str("value"))
        else -> emptySet()
    }

    private fun operations(b: JsonObject): List<JsonObject> =
        (b["Operations"] ?: b["operations"])?.jsonArray?.map { it.jsonObject } ?: throw Invalid("'Operations' is required")

    private fun body(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) throw Invalid("A JSON body is required")
        return runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse { throw Invalid("The body is not a JSON object") }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    /** Entra sends booleans as the strings "True" and "False". */
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.lowercase().toBooleanStrictOrNull() }

    private fun ok(body: JsonElement, status: HttpStatus = HttpStatus.OK): ResponseEntity<String> =
        ResponseEntity.status(status).contentType(SCIM_JSON).body(JSON.encodeToString(JsonElement.serializer(), body))

    private suspend fun scim(authorization: String?, block: suspend (Principal) -> ResponseEntity<String>): ResponseEntity<String> = try {
        if (authorization == null || !authorization.startsWith("Bearer ", ignoreCase = true)) throw Unauthenticated("A bearer token is required")
        val p = auth.bearer(authorization.substring(7).trim())
        svc.requireScim(p)
        block(p)
    } catch (e: DomainException) {
        val (status, type) = when {
            e is InvalidFilter -> HttpStatus.BAD_REQUEST to "invalidFilter"
            e.code == "not_found" -> HttpStatus.NOT_FOUND to null
            e.code == "conflict" -> HttpStatus.CONFLICT to "uniqueness"
            e.code == "unauthenticated" -> HttpStatus.UNAUTHORIZED to null
            e.code == "forbidden" -> HttpStatus.FORBIDDEN to null
            else -> HttpStatus.BAD_REQUEST to "invalidValue"
        }
        ok(buildJsonObject {
            putJsonArray("schemas") { add(JsonPrimitive(ERROR)) }
            put("status", status.value().toString())
            type?.let { put("scimType", it) }
            put("detail", e.message ?: e.code)
        }, status)
    }
}
