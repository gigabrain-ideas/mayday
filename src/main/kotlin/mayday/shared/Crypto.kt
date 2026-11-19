package mayday.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun b64(bytes: ByteArray): String = enc.encodeToString(bytes)
    fun unb64(s: String): ByteArray = dec.decode(s)

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmacHex(key: String, data: String): String =
        hmac(key.toByteArray(), data.toByteArray()).joinToString("") { "%02x".format(it) }

    fun equal(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
}

/** Compact HS256 JWS so that agents can read the claims with any JWT library. */
class TokenSigner(private val key: ByteArray) {
    private val header = Crypto.b64("""{"alg":"HS256","typ":"JWT"}""".toByteArray())

    fun sign(claims: JsonObject): String {
        val body = Crypto.b64(JSON.encodeToString(JsonObject.serializer(), claims).toByteArray())
        val sig = Crypto.b64(Crypto.hmac(key, "$header.$body".toByteArray()))
        return "$header.$body.$sig"
    }

    fun verify(token: String): JsonObject? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != header) return null
        val expected = Crypto.hmac(key, "${parts[0]}.${parts[1]}".toByteArray())
        val actual = runCatching { Crypto.unb64(parts[2]) }.getOrNull() ?: return null
        if (!Crypto.equal(expected, actual)) return null
        return runCatching { JSON.parseToJsonElement(String(Crypto.unb64(parts[1]))).jsonObject }.getOrNull()
    }
}

/** AES-256-GCM box for secrets at rest (LLM keys, webhook secrets, MCP credentials). */
class SecretBox(masterKey: ByteArray) {
    private val key = SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(masterKey), "AES")
    private val random = SecureRandom()

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "v1." + Crypto.b64(iv + c.doFinal(plain.toByteArray()))
    }

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        require(sealed.startsWith("v1.")) { "unknown secret format" }
        val raw = Crypto.unb64(sealed.removePrefix("v1."))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}
