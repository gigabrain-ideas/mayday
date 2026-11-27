package mayday.adapters.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import mayday.audit.AuditActor
import mayday.audit.AuditContext
import mayday.audit.AuditCategory
import mayday.audit.AuditTrail
import mayday.audit.record
import mayday.identity.audit
import mayday.identity.target
import kotlinx.coroutines.withContext
import mayday.identity.IdentityService
import mayday.identity.Principal
import mayday.identity.User
import mayday.shared.Forbidden
import mayday.shared.Unauthenticated

/**
 * Resolves the credentials of a request to a principal. Every driving adapter (gRPC, gRPC-Web, HTTP, MCP) uses it.
 * Order: bearer token (API token, delegated token or SSO access token), then the session cookie.
 */
class Authenticator(
    private val identity: IdentityService,
    val oidc: OidcClient?,
    val sessions: Sessions,
    val devLogin: Boolean,
    val publicUrl: String,
    private val audit: AuditTrail = AuditTrail.none,
) {
    suspend fun principal(authorization: String?, cookieHeader: String?): Principal {
        if (authorization != null && authorization.startsWith("Bearer ", ignoreCase = true)) return bearer(authorization.substring(7).trim())
        val ctx = currentCoroutineContext()[AuditContext]
        val raw = cookie(cookieHeader, Sessions.COOKIE)
        if (raw != null) ctx?.caller = AuditActor.anonymous.copy(credential = "session")
        val userId = sessions.verify(raw) ?: throw Unauthenticated()
        val user = runCatching { identity.user(userId) }.getOrNull() ?: throw Unauthenticated()
        return identity.principalForUser(user).also { ctx?.caller = it.audit("session") }
    }

    /**
     * Cookie sessions on HTTP endpoints that change data need the X-Requested-With header: a cross-site page cannot send it
     * without CORS. gRPC-Web needs no such header because its content type also forces a CORS preflight.
     */
    suspend fun principalForHttp(authorization: String?, cookieHeader: String?, mutating: Boolean, requestedWith: String?): Principal {
        val p = principal(authorization, cookieHeader)
        val usedCookie = authorization == null || !authorization.startsWith("Bearer ", ignoreCase = true)
        if (usedCookie && mutating && requestedWith != "mayday") throw Forbidden("Missing X-Requested-With header")
        return p
    }

    suspend fun bearer(token: String): Principal {
        val ctx = currentCoroutineContext()[AuditContext]
        val sso = oidc != null && !token.startsWith("mdy_") && oidc.looksLikeOurs(token)
        val credential = when {
            sso -> "sso_token"
            token.startsWith("mdy_") -> "api_token"
            else -> "delegated_token"
        }
        ctx?.caller = AuditActor.anonymous.copy(credential = credential)
        val p = if (sso) {
            val id = withContext(Dispatchers.IO) { oidc!!.verifyAccessToken(token) }
            identity.principalForUser(identity.upsertSso(id.subject, id.email, id.name, id.groups, id.emailVerified))
        } else identity.authenticate(token)
        ctx?.caller = p.audit(credential)
        return p
    }

    suspend fun ssoUser(id: OidcIdentity): User =
        identity.upsertSso(id.subject, id.email, id.name, id.groups, id.emailVerified).also { signedIn(it, "sso") }

    suspend fun exchange(subject: Principal, actor: Principal, ttl: java.time.Duration): String = identity.exchange(subject, actor, ttl)

    suspend fun devUser(email: String, name: String): User {
        if (!devLogin) throw Forbidden("Development login is turned off")
        return identity.upsertSso("dev|${email.lowercase()}", email, name, emailVerified = true).also { signedIn(it, "dev_login") }
    }

    private suspend fun signedIn(user: User, method: String) {
        val by = identity.principalForUser(user).audit("session")
        currentCoroutineContext()[AuditContext]?.caller = by
        audit.record(AuditCategory.AUTHENTICATION, "sign_in", by, user.target(), mapOf("method" to method))
    }

    suspend fun signedOut(cookieHeader: String?) {
        val user = sessions.verify(cookie(cookieHeader, Sessions.COOKIE))?.let { runCatching { identity.user(it) }.getOrNull() } ?: return
        val by = identity.principalForUser(user).audit("session")
        currentCoroutineContext()[AuditContext]?.caller = by
        audit.record(AuditCategory.AUTHENTICATION, "sign_out", by, user.target())
    }

    companion object {
        fun cookie(header: String?, name: String): String? = header.orEmpty().split(';')
            .map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}
