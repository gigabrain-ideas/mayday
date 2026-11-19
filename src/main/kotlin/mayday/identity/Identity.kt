package mayday.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mayday.directory.TeamRepository
import mayday.shared.Actor
import mayday.shared.ActorKind
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.Crypto
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Page
import mayday.shared.TokenSigner
import mayday.shared.Ts
import mayday.shared.Unauthenticated
import java.time.Duration

@Serializable
enum class OrgRole(val rank: Int) { VIEWER(0), MEMBER(1), ADMIN(2) }

@Serializable
enum class TeamRole { MEMBER, ADMIN }

@Serializable
enum class TokenScope { READ, WRITE, ADMIN, ALERTS_INGEST }

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String,
    val orgRole: OrgRole = OrgRole.MEMBER,
    val teams: Map<String, TeamRole> = emptyMap(),
    val projectAdmin: Set<String> = emptySet(),
    val externalId: String? = null,
    val disabled: Boolean = false,
    val createdAt: Ts,
)

@Serializable
enum class OwnerKind { USER, AGENT }

@Serializable
data class ApiToken(
    val id: String,
    val name: String,
    val ownerKind: OwnerKind,
    val ownerId: String,
    val hash: String,
    val prefix: String,
    val scopes: Set<TokenScope>,
    val createdAt: Ts,
    val expiresAt: Ts? = null,
)

interface UserRepository {
    fun get(id: String): User?
    fun byEmail(email: String): User?
    fun byExternalId(externalId: String): User?
    fun list(query: String?, cursor: String?, limit: Int): Page<User>
    fun count(): Long
    fun save(user: User)
}

interface TokenRepository {
    fun byHash(hash: String): ApiToken?
    fun byOwner(kind: OwnerKind, ownerId: String): List<ApiToken>
    fun get(id: String): ApiToken?
    fun save(token: ApiToken)
    fun delete(id: String)
}

/** Agent identity as seen by the identity context; the agents context provides it. */
data class AgentIdentity(val id: String, val name: String, val role: OrgRole, val enabled: Boolean)

fun interface AgentIdentityLookup {
    fun find(agentId: String): AgentIdentity?
}

/**
 * Who performs a request. [user] is the identity whose RBAC applies. When an agent acts on behalf of a user,
 * [actor] is the agent and [user] the delegating human. An agent that acts with its own identity has no user.
 */
data class Principal(
    val actor: Actor,
    val user: User?,
    val agentRole: OrgRole?,
    val scopes: Set<TokenScope>,
    val runId: String? = null,
) {
    val role: OrgRole get() = user?.orgRole ?: agentRole ?: OrgRole.VIEWER
    val isDelegated: Boolean get() = actor.kind == ActorKind.AGENT && user != null
    val isAgent: Boolean get() = actor.kind == ActorKind.AGENT

    fun has(scope: TokenScope) = scope in scopes

    companion object {
        val ALL = TokenScope.entries.toSet()
        val system = Principal(Actor.system, null, OrgRole.ADMIN, ALL)
    }
}

object Access {
    fun requireRead(p: Principal) {
        if (!p.has(TokenScope.READ)) throw Forbidden("The token does not allow read access")
    }

    fun requireWrite(p: Principal) {
        requireRead(p)
        if (!p.has(TokenScope.WRITE)) throw Forbidden("The token does not allow write access")
        if (p.role.rank < OrgRole.MEMBER.rank) throw Forbidden("Viewers cannot change data")
    }

    fun isAdmin(p: Principal) = p.has(TokenScope.ADMIN) && p.role == OrgRole.ADMIN

    fun requireAdmin(p: Principal) {
        if (!isAdmin(p)) throw Forbidden("This action requires an administrator")
    }

    fun isTeamAdmin(p: Principal, teamId: String, projectId: String?): Boolean {
        if (isAdmin(p)) return true
        if (!p.has(TokenScope.WRITE)) return false
        val u = p.user ?: return false
        return u.teams[teamId] == TeamRole.ADMIN || (projectId != null && projectId in u.projectAdmin)
    }

    fun isProjectAdmin(p: Principal, projectId: String): Boolean {
        if (isAdmin(p)) return true
        if (!p.has(TokenScope.WRITE)) return false
        return p.user?.let { projectId in it.projectAdmin } ?: false
    }
}

@Serializable
data class IssuedToken(val token: ApiToken, val secret: String)

class IdentityService(
    private val users: UserRepository,
    private val tokens: TokenRepository,
    private val teams: TeamRepository,
    private val agents: AgentIdentityLookup,
    private val signer: TokenSigner,
    private val clock: Clock,
    private val bootstrapAdmins: Set<String>,
) {
    fun user(id: String) = users.get(id) ?: throw NotFound("User")
    fun list(query: String?, cursor: String?, limit: Int) = users.list(query, cursor, limit.coerceIn(1, 500))

    /** Find or create the user for a verified SSO login. */
    fun upsertSso(externalId: String, email: String, name: String): User {
        val normalized = email.trim().lowercase()
        val existing = users.byExternalId(externalId) ?: users.byEmail(normalized)
        val user = if (existing != null) {
            existing.copy(externalId = externalId, email = normalized, name = name.ifBlank { existing.name })
        } else {
            val role = if (normalized in bootstrapAdmins || users.count() == 0L) OrgRole.ADMIN else OrgRole.MEMBER
            User(Ids.new(), normalized, name.ifBlank { normalized.substringBefore('@') }, role, externalId = externalId, createdAt = clock.now())
        }
        if (user.disabled) throw Forbidden("This account is disabled")
        users.save(user)
        return user
    }

    fun createUser(by: Principal, email: String, name: String, role: OrgRole): User {
        Access.requireAdmin(by)
        val normalized = email.trim().lowercase()
        if (!normalized.contains('@')) throw Invalid("A valid email is required")
        if (users.byEmail(normalized) != null) throw Conflict("A user with this email exists")
        return User(Ids.new(), normalized, name.ifBlank { normalized.substringBefore('@') }, role, createdAt = clock.now())
            .also(users::save)
    }

    fun updateUser(
        by: Principal,
        id: String,
        name: String? = null,
        role: OrgRole? = null,
        teamsUpdate: Map<String, TeamRole>? = null,
        projectAdmin: Set<String>? = null,
        disabled: Boolean? = null,
    ): User {
        Access.requireAdmin(by)
        val u = user(id)
        teamsUpdate?.keys?.forEach { if (teams.get(it) == null) throw Invalid("Unknown team $it") }
        if (by.user?.id == id && (role != null && role != OrgRole.ADMIN || disabled == true)) {
            throw Conflict("You cannot remove your own administrator access")
        }
        return u.copy(
            name = name ?: u.name,
            orgRole = role ?: u.orgRole,
            teams = teamsUpdate ?: u.teams,
            projectAdmin = projectAdmin ?: u.projectAdmin,
            disabled = disabled ?: u.disabled,
        ).also(users::save)
    }

    fun issueToken(by: Principal, name: String, scopes: Set<TokenScope>, ttl: Duration?): IssuedToken {
        val owner = by.user ?: throw Forbidden("Only users can create personal tokens")
        if (by.isDelegated) throw Forbidden("Agents cannot create tokens for users")
        val allowed = scopes.filter { it != TokenScope.ADMIN || owner.orgRole == OrgRole.ADMIN }.toSet()
        return mint(OwnerKind.USER, owner.id, name, allowed.ifEmpty { setOf(TokenScope.READ) }, ttl)
    }

    fun issueAgentToken(agentId: String, name: String, scopes: Set<TokenScope>): IssuedToken =
        mint(OwnerKind.AGENT, agentId, name, scopes, null)

    fun tokensOf(kind: OwnerKind, ownerId: String) = tokens.byOwner(kind, ownerId)

    fun revokeToken(by: Principal, id: String) {
        val t = tokens.get(id) ?: throw NotFound("Token")
        val own = t.ownerKind == OwnerKind.USER && t.ownerId == by.user?.id && !by.isDelegated
        if (!own) Access.requireAdmin(by)
        tokens.delete(id)
    }

    fun revokeAgentTokens(agentId: String) = tokens.byOwner(OwnerKind.AGENT, agentId).forEach { tokens.delete(it.id) }

    private fun mint(kind: OwnerKind, ownerId: String, name: String, scopes: Set<TokenScope>, ttl: Duration?): IssuedToken {
        val secret = "mdy_" + Ids.secret(30)
        val token = ApiToken(
            id = Ids.new(),
            name = name.ifBlank { "token" },
            ownerKind = kind,
            ownerId = ownerId,
            hash = Crypto.sha256Hex(secret),
            prefix = secret.take(10),
            scopes = scopes,
            createdAt = clock.now(),
            expiresAt = ttl?.let { clock.now().plus(it) },
        )
        tokens.save(token)
        return IssuedToken(token, secret)
    }

    fun principalForUser(user: User, scopes: Set<TokenScope> = Principal.ALL): Principal {
        if (user.disabled) throw Forbidden("This account is disabled")
        return Principal(Actor(ActorKind.USER, user.id, user.name), user, null, scopes)
    }

    /** Resolve an opaque API token (mdy_...) or a delegated token (JWS) to a principal. */
    fun authenticate(bearer: String): Principal {
        if (bearer.startsWith("mdy_")) return authenticateApiToken(bearer)
        return authenticateDelegated(bearer) ?: throw Unauthenticated("Invalid token")
    }

    private fun authenticateApiToken(secret: String): Principal {
        val t = tokens.byHash(Crypto.sha256Hex(secret)) ?: throw Unauthenticated("Invalid token")
        if (t.expiresAt?.isBefore(clock.now()) == true) throw Unauthenticated("Token expired")
        return when (t.ownerKind) {
            OwnerKind.USER -> {
                val u = users.get(t.ownerId) ?: throw Unauthenticated("Invalid token")
                principalForUser(u, t.scopes)
            }
            OwnerKind.AGENT -> {
                val a = agents.find(t.ownerId)?.takeIf { it.enabled } ?: throw Unauthenticated("Agent is disabled or unknown")
                Principal(Actor(ActorKind.AGENT, a.id, a.name), null, a.role, t.scopes)
            }
        }
    }

    /**
     * Mint an on-behalf-of token: the agent acts with the RBAC of [user]. The token is short-lived and bound to a run.
     */
    fun delegate(user: User, agent: AgentIdentity, runId: String?, ttl: Duration, scopes: Set<TokenScope>): String {
        val now = clock.now()
        return signer.sign(buildJsonObject {
            put("iss", "mayday")
            put("typ", "obo")
            put("sub", user.id)
            put("act", buildJsonObject { put("sub", "agent:${agent.id}") })
            if (runId != null) put("run", runId)
            put("scope", scopes.joinToString(" ") { it.name.lowercase() })
            put("iat", now.epochSecond)
            put("exp", now.plus(ttl).epochSecond)
        })
    }

    /** RFC 8693 style exchange: an agent presents its own token and a user's token and receives a delegated token. */
    fun exchange(subject: Principal, actor: Principal, ttl: Duration): String {
        val user = subject.user ?: throw Forbidden("The subject token must identify a user")
        if (subject.isAgent) throw Forbidden("The subject token must belong to a user")
        if (!actor.isAgent || actor.isDelegated) throw Forbidden("The actor token must belong to an agent")
        val agent = agents.find(actor.actor.id) ?: throw Forbidden("Unknown agent")
        return delegate(user, agent, null, ttl, subject.scopes)
    }

    private fun authenticateDelegated(token: String): Principal? {
        val claims = signer.verify(token) ?: return null
        if (claims["typ"]?.jsonPrimitive?.content != "obo") return null
        val exp = claims["exp"]?.jsonPrimitive?.long ?: return null
        if (exp < clock.now().epochSecond) throw Unauthenticated("Token expired")
        val user = users.get(claims["sub"]!!.jsonPrimitive.content) ?: return null
        if (user.disabled) throw Forbidden("This account is disabled")
        val actorSub = (claims["act"] as? kotlinx.serialization.json.JsonObject)?.get("sub")?.jsonPrimitive?.content ?: return null
        val agent = agents.find(actorSub.removePrefix("agent:"))?.takeIf { it.enabled } ?: return null
        val scopes = claims["scope"]?.jsonPrimitive?.content.orEmpty().split(' ')
            .mapNotNull { s -> TokenScope.entries.firstOrNull { it.name.equals(s, true) } }.toSet()
        val run = (claims["run"] as? JsonPrimitive)?.content
        return Principal(Actor(ActorKind.AGENT, agent.id, agent.name, onBehalfOf = user.id), user, agent.role, scopes, run)
    }
}
