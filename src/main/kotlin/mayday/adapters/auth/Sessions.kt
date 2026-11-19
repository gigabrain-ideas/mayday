package mayday.adapters.auth

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mayday.shared.TokenSigner
import java.time.Duration
import java.time.Instant

/** Stateless sessions: a signed cookie, so any replica can serve any request. */
class Sessions(private val signer: TokenSigner, private val ttl: Duration, private val secure: Boolean) {
    companion object {
        const val COOKIE = "mayday_session"
        const val FLOW_COOKIE = "mayday_oidc"
    }

    fun issue(userId: String): String = signer.sign(buildJsonObject {
        put("typ", "session"); put("sub", userId); put("exp", Instant.now().plus(ttl).epochSecond)
    })

    fun verify(token: String?): String? {
        val c = token?.let(signer::verify) ?: return null
        if (c["typ"]?.jsonPrimitive?.content != "session") return null
        if ((c["exp"]?.jsonPrimitive?.long ?: 0) < Instant.now().epochSecond) return null
        return c["sub"]?.jsonPrimitive?.content
    }

    fun flow(state: String, nonce: String, verifier: String, redirect: String): String = signer.sign(buildJsonObject {
        put("typ", "flow"); put("state", state); put("nonce", nonce); put("verifier", verifier); put("redirect", redirect)
        put("exp", Instant.now().plusSeconds(600).epochSecond)
    })

    data class Flow(val state: String, val nonce: String, val verifier: String, val redirect: String)

    fun readFlow(token: String?): Flow? {
        val c = token?.let(signer::verify) ?: return null
        if (c["typ"]?.jsonPrimitive?.content != "flow") return null
        if ((c["exp"]?.jsonPrimitive?.long ?: 0) < Instant.now().epochSecond) return null
        return Flow(c["state"]!!.jsonPrimitive.content, c["nonce"]!!.jsonPrimitive.content, c["verifier"]!!.jsonPrimitive.content, c["redirect"]!!.jsonPrimitive.content)
    }

    fun cookie(name: String, value: String, maxAge: Duration): String =
        "$name=$value; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge.seconds}" + if (secure) "; Secure" else ""

    fun sessionCookie(userId: String) = cookie(COOKIE, issue(userId), ttl)
    fun clear(name: String) = cookie(name, "", Duration.ZERO)
}
