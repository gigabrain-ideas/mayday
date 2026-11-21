package mayday.adapters.auth

import kotlinx.coroutines.Dispatchers
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
) {
    suspend fun principal(authorization: String?, cookieHeader: String?): Principal {
        if (authorization != null && authorization.startsWith("Bearer ", ignoreCase = true)) return bearer(authorization.substring(7).trim())
        val userId = sessions.verify(cookie(cookieHeader, Sessions.COOKIE)) ?: throw Unauthenticated()
        val user = runCatching { identity.user(userId) }.getOrNull() ?: throw Unauthenticated()
        return identity.principalForUser(user)
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
        if (oidc != null && !token.startsWith("mdy_") && oidc.looksLikeOurs(token)) {
            val id = withContext(Dispatchers.IO) { oidc.verifyAccessToken(token) }
            return identity.principalForUser(identity.upsertSso(id.subject, id.email, id.name, id.groups))
        }
        return identity.authenticate(token)
    }

    suspend fun ssoUser(subject: String, email: String, name: String, groups: Set<String>? = null): User = identity.upsertSso(subject, email, name, groups)

    suspend fun exchange(subject: Principal, actor: Principal, ttl: java.time.Duration): String = identity.exchange(subject, actor, ttl)

    suspend fun devUser(email: String, name: String): User {
        if (!devLogin) throw Forbidden("Development login is turned off")
        return identity.upsertSso("dev|${email.lowercase()}", email, name)
    }

    companion object {
        fun cookie(header: String?, name: String): String? = header.orEmpty().split(';')
            .map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}
