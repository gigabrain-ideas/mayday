package mayday.adapters.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mayday.shared.Crypto
import mayday.shared.JSON
import mayday.shared.Unauthenticated
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

data class OidcSettings(
    val issuer: String,
    val clientId: String,
    val clientSecret: String,
    val scopes: String,
    val redirectUri: String,
    val audiences: Set<String>,
    /** Claim with the group list. A dotted path reads a nested claim, for example "realm_access.roles". Blank turns group sync off. */
    val groupsClaim: String = "groups",
)

/**
 * [groups] is null when group sync is off or the token moved the claim to a distributed source (group overage): then the groups are unknown.
 * A missing claim means no groups, because Entra ID and Keycloak leave the claim out for a user without groups.
 * [emailVerified] is true only for an `email` claim that the provider marks verified (`email_verified`, Entra ID `xms_edov`).
 */
data class OidcIdentity(val subject: String, val email: String, val name: String, val groups: Set<String>? = null, val emailVerified: Boolean = false)

/** OpenID Connect relying party: authorization code flow with PKCE, ID token and access token validation. */
class OidcClient(private val s: OidcSettings) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build()
    @Volatile private var discovery: JsonObject? = null
    @Volatile private var keys: Map<String, Pair<String, PublicKey>> = emptyMap()
    @Volatile private var keysAt: Instant = Instant.EPOCH

    private fun get(url: String): JsonObject {
        val r = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() != 200) throw IllegalStateException("GET $url answered HTTP ${r.statusCode()}")
        return JSON.parseToJsonElement(r.body()).jsonObject
    }

    private fun meta(): JsonObject = discovery ?: get(s.issuer.trimEnd('/') + "/.well-known/openid-configuration").also { discovery = it }
    private fun meta(key: String) = meta()[key]?.jsonPrimitive?.content ?: throw IllegalStateException("The identity provider has no $key")

    fun authorizeUrl(state: String, nonce: String, verifier: String): String {
        val challenge = Crypto.b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val q = mapOf(
            "response_type" to "code", "client_id" to s.clientId, "redirect_uri" to s.redirectUri, "scope" to s.scopes,
            "state" to state, "nonce" to nonce, "code_challenge" to challenge, "code_challenge_method" to "S256",
        ).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        return meta("authorization_endpoint") + (if (meta("authorization_endpoint").contains('?')) "&" else "?") + q
    }

    fun logoutUrl(postLogout: String): String? = meta()["end_session_endpoint"]?.jsonPrimitive?.content?.let {
        "$it?client_id=${enc(s.clientId)}&post_logout_redirect_uri=${enc(postLogout)}"
    }

    fun exchange(code: String, verifier: String, nonce: String): OidcIdentity {
        val form = mapOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to s.redirectUri,
            "client_id" to s.clientId, "client_secret" to s.clientSecret, "code_verifier" to verifier,
        ).filterValues { it.isNotEmpty() }.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val r = http.send(
            HttpRequest.newBuilder(URI(meta("token_endpoint"))).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (r.statusCode() != 200) throw Unauthenticated("The identity provider refused the login (HTTP ${r.statusCode()})")
        val idToken = JSON.parseToJsonElement(r.body()).jsonObject["id_token"]?.jsonPrimitive?.content
            ?: throw Unauthenticated("The identity provider returned no ID token")
        val claims = verify(idToken, setOf(s.clientId))
        if (claims["nonce"]?.jsonPrimitive?.content != nonce) throw Unauthenticated("Invalid login nonce")
        return identity(claims)
    }

    /** Validate a JWT access token from the same issuer; used for on-behalf-of calls from agents. */
    fun verifyAccessToken(token: String): OidcIdentity = identity(verify(token, s.audiences + s.clientId))

    fun looksLikeOurs(token: String): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        val payload = runCatching { JSON.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject }.getOrNull() ?: return false
        return payload["iss"]?.jsonPrimitive?.content?.trimEnd('/') == s.issuer.trimEnd('/')
    }

    private fun identity(c: JsonObject): OidcIdentity {
        val sub = c["sub"]?.jsonPrimitive?.content ?: throw Unauthenticated("Token has no subject")
        val claimed = c["email"]?.jsonPrimitive?.content
        val email = claimed ?: (c["preferred_username"] ?: c["upn"])?.jsonPrimitive?.content ?: throw Unauthenticated("Token has no email claim")
        val name = c["name"]?.jsonPrimitive?.content ?: email
        val verified = claimed != null && (flag(c["email_verified"]) || flag(c["xms_edov"]))
        return OidcIdentity("${s.issuer}|$sub", email, name, groups(c, s.groupsClaim), verified)
    }

    private fun verify(jwt: String, audiences: Set<String>): JsonObject {
        val parts = jwt.split('.')
        if (parts.size != 3) throw Unauthenticated("Malformed token")
        val header = JSON.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
        val alg = header["alg"]?.jsonPrimitive?.content ?: throw Unauthenticated("Token has no algorithm")
        val kid = header["kid"]?.jsonPrimitive?.content
        val key = key(kid, alg)
        val sig = Signature.getInstance(when (alg) {
            "RS256" -> "SHA256withRSA"; "RS384" -> "SHA384withRSA"; "RS512" -> "SHA512withRSA"
            "ES256" -> "SHA256withECDSAinP1363Format"; "ES384" -> "SHA384withECDSAinP1363Format"
            else -> throw Unauthenticated("Unsupported token algorithm $alg")
        })
        sig.initVerify(key)
        sig.update("${parts[0]}.${parts[1]}".toByteArray())
        if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) throw Unauthenticated("Invalid token signature")
        val c = JSON.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
        val now = Instant.now().epochSecond
        if (c["iss"]?.jsonPrimitive?.content?.trimEnd('/') != s.issuer.trimEnd('/')) throw Unauthenticated("Wrong token issuer")
        if ((c["exp"]?.jsonPrimitive?.long ?: 0) < now - 60) throw Unauthenticated("Token expired")
        c["nbf"]?.jsonPrimitive?.long?.let { if (it > now + 60) throw Unauthenticated("Token not valid yet") }
        val aud = when (val a = c["aud"]) {
            is JsonArray -> a.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(a.content)
            else -> emptyList()
        }
        if (aud.none { it in audiences }) throw Unauthenticated("Wrong token audience")
        return c
    }

    private fun key(kid: String?, alg: String): PublicKey {
        fun find() = keys.entries.firstOrNull { (k, v) -> (kid == null || k == kid) && v.first.take(2) == alg.take(2) }?.value?.second
        find()?.let { return it }
        if (keysAt.isAfter(Instant.now().minusSeconds(30))) throw Unauthenticated("Unknown signing key")
        keys = loadKeys()
        keysAt = Instant.now()
        return find() ?: throw Unauthenticated("Unknown signing key")
    }

    private fun loadKeys(): Map<String, Pair<String, PublicKey>> {
        val jwks = get(meta("jwks_uri"))["keys"] as? JsonArray ?: return emptyMap()
        return jwks.mapNotNull { k ->
            val o = k.jsonObject
            if (o["use"]?.jsonPrimitive?.content.let { it != null && it != "sig" }) return@mapNotNull null
            val kid = o["kid"]?.jsonPrimitive?.content ?: "default"
            runCatching {
                when (o["kty"]?.jsonPrimitive?.content) {
                    "RSA" -> kid to ("RS" to KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(big(o, "n"), big(o, "e"))))
                    "EC" -> {
                        val curve = when (o["crv"]?.jsonPrimitive?.content) { "P-256" -> "secp256r1"; "P-384" -> "secp384r1"; else -> error("curve") }
                        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(curve)) }.getParameterSpec(ECParameterSpec::class.java)
                        kid to ("ES" to KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(big(o, "x"), big(o, "y")), params)))
                    }
                    else -> null
                }
            }.getOrNull()
        }.toMap()
    }

    companion object {
        /** OpenID Connect Core 5.6.2: a claim listed in _claim_names is not in the token. Then the group list is unknown, not empty. */
        fun groups(c: JsonObject, path: String): Set<String>? {
            val p = path.trim()
            if (p.isEmpty()) return null
            val first = p.substringBefore('.')
            if ((c["_claim_names"] as? JsonObject)?.containsKey(first) == true) return null
            var node: kotlinx.serialization.json.JsonElement? = c
            for (part in p.split('.')) node = (node as? JsonObject)?.get(part)
            return when (val v = node) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.toSet()
                is JsonPrimitive -> if (v.isString) v.content.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet() else null
                null -> emptySet()
                else -> null
            }
        }
    }

    private fun flag(e: kotlinx.serialization.json.JsonElement?) = (e as? JsonPrimitive)?.content == "true"
    private fun big(o: JsonObject, k: String) = BigInteger(1, Base64.getUrlDecoder().decode(o[k]!!.jsonPrimitive.content))
    private fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8)
}
