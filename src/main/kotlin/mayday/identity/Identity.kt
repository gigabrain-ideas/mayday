package mayday.identity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mayday.audit.AuditActor
import mayday.audit.AuditCategory
import mayday.audit.AuditParty
import mayday.audit.AuditTarget
import mayday.audit.AuditTrail
import mayday.audit.record
import mayday.directory.TeamRepository
import mayday.policy.Keys
import mayday.policy.OrgSettingListener
import mayday.policy.PolicyReader
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
enum class TokenScope { READ, WRITE, ADMIN, ALERTS_INGEST, SCIM }

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
    /** The user lets agents keep the admin scope in token exchange. Works only when the organization allows it. */
    val allowAgentAdmin: Boolean = false,
    /** Groups from the groups claim of the last sign-in. */
    val idpGroups: Set<String> = emptySet(),
    /** Keys of the SCIM groups that contain the user. */
    val scimGroups: Set<String> = emptySet(),
    /** The externalId that the SCIM client gave the user. */
    val scimExternalId: String? = null,
) {
    val groups: Set<String> get() = idpGroups + scimGroups
}

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
    suspend fun get(id: String): User?
    suspend fun byEmail(email: String): User?
    suspend fun byExternalId(externalId: String): User?
    suspend fun list(query: String?, cursor: String?, limit: Int): Page<User>
    suspend fun byIds(ids: Collection<String>): List<User>
    suspend fun count(): Long
    suspend fun save(user: User)
    suspend fun withAgentAdmin(): List<User>
    suspend fun byTeam(teamId: String): List<User>

    /** Users with at least one of [keys] in [User.idpGroups] or [User.scimGroups]. */
    suspend fun withGroups(keys: Set<String>): List<User>
    suspend fun byScimExternalId(externalId: String): User?
}

/** Told when a person leaves a team, so other contexts (on-call rotations) can follow. */
interface TeamMembershipListener {
    suspend fun membershipRemoved(teamId: String, userId: String)
}

/** Applies the groups of a verified sign-in to team membership. The provisioning context implements it. */
interface GroupMembershipSync {
    suspend fun signedIn(user: User, groups: Set<String>): User
}

@Serializable
data class TeamMember(val userId: String, val name: String, val email: String, val role: TeamRole, val managed: Boolean, val disabled: Boolean = false)

interface TokenRepository {
    suspend fun byHash(hash: String): ApiToken?
    suspend fun byOwner(kind: OwnerKind, ownerId: String): List<ApiToken>
    suspend fun get(id: String): ApiToken?
    suspend fun save(token: ApiToken)
    suspend fun delete(id: String)
}

/** Agent identity as seen by the identity context; the agents context provides it. */
data class AgentIdentity(val id: String, val name: String, val role: OrgRole, val enabled: Boolean)

interface AgentIdentityLookup {
    suspend fun find(agentId: String): AgentIdentity?

    /** True while the run exists and has not finished. A delegated token bound to a run works only then. */
    suspend fun runActive(runId: String): Boolean
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
        val ADMIN_ONLY = setOf(TokenScope.ADMIN, TokenScope.SCIM)

        /** Scopes of a delegated run token: the agent can read and write as the user, never administer. */
        val DELEGATED = setOf(TokenScope.READ, TokenScope.WRITE)
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

fun User.auditParty() = AuditParty("user", id, name, email)
fun User.target() = AuditTarget("user", id, name)
private fun User.principalActor(credential: String) = AuditActor(auditParty(), role = orgRole.name, credential = credential)
private fun ApiToken.target() = AuditTarget("token", id, name)
private fun Set<TokenScope>.lowercase() = map { it.name.lowercase() }.sorted().joinToString(",")
private fun ApiToken.auditDetails() = buildMap {
    put("owner", "${ownerKind.name.lowercase()}:$ownerId")
    put("prefix", prefix)
    put("scopes", scopes.lowercase())
    expiresAt?.let { put("expiresAt", it.toString()) }
}

private fun changes(before: User, after: User): Map<String, String> = buildMap {
    if (before.name != after.name) put("name", after.name)
    if (before.orgRole != after.orgRole) { put("role.from", before.orgRole.name); put("role.to", after.orgRole.name) }
    if (before.disabled != after.disabled) put("disabled", after.disabled.toString())
    val added = after.teams.keys - before.teams.keys
    val removed = before.teams.keys - after.teams.keys
    val changed = after.teams.filter { (k, v) -> before.teams[k] != null && before.teams[k] != v }
    if (added.isNotEmpty()) put("teams.added", added.joinToString(",") { "$it:${after.teams[it]}" })
    if (removed.isNotEmpty()) put("teams.removed", removed.joinToString(","))
    if (changed.isNotEmpty()) put("teams.changed", changed.entries.joinToString(",") { "${it.key}:${it.value}" })
    if (before.projectAdmin != after.projectAdmin) put("projectAdmin", after.projectAdmin.sorted().joinToString(","))
}

/** The principal as the audit stream shows it: the actor and, when an agent acts for a person, that person. */
fun Principal.audit(credential: String? = null) = AuditActor(
    actor = when (actor.kind) {
        ActorKind.USER -> user?.auditParty() ?: AuditParty("user", actor.id, actor.name)
        ActorKind.AGENT -> AuditParty("agent", actor.id, actor.name)
        ActorKind.SYSTEM -> AuditParty.system
    },
    onBehalfOf = if (isDelegated) user?.auditParty() else null,
    role = role.name,
    scopes = scopes.map { it.name.lowercase() }.sorted(),
    runId = runId,
    credential = credential,
)

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
    private val policy: PolicyReader,
    private val audit: AuditTrail = AuditTrail.none,
) : OrgSettingListener {
    var membership: TeamMembershipListener? = null
    var groupSync: GroupMembershipSync? = null

    suspend fun user(id: String) = users.get(id) ?: throw NotFound("User")
    suspend fun list(query: String?, cursor: String?, limit: Int) = users.list(query, cursor, limit.coerceIn(1, 500))
    suspend fun byIds(ids: Collection<String>): List<User> {
        if (ids.size > 500) throw Invalid("At most 500 user ids")
        return users.byIds(ids.toSet())
    }

    /**
     * Find or create the user for a verified SSO login. An account found only by email is linked when it never signed in and the
     * identity provider verified the email ([emailVerified]). Otherwise anybody who can put an email in a token would sign in as that person.
     */
    suspend fun upsertSso(externalId: String, email: String, name: String, groups: Set<String>? = null, emailVerified: Boolean = false): User {
        val normalized = email.trim().lowercase()
        val existing = users.byExternalId(externalId) ?: users.byEmail(normalized)?.also {
            if (it.externalId != null || !emailVerified) throw Forbidden("An account with this email exists. Sign in with the identity that it uses, or ask an administrator.")
        }
        val user = if (existing != null) {
            existing.copy(externalId = externalId, email = normalized, name = name.ifBlank { existing.name })
        } else {
            val role = if (normalized in bootstrapAdmins || users.count() == 0L) OrgRole.ADMIN else OrgRole.MEMBER
            User(Ids.new(), normalized, name.ifBlank { normalized.substringBefore('@') }, role, externalId = externalId, createdAt = clock.now())
        }
        if (user.disabled) throw Forbidden("This account is disabled")
        users.save(user)
        if (existing == null) {
            audit.record(AuditCategory.IDENTITY, "user.provisioned", user.principalActor("sso_token"), user.target(),
                mapOf("role" to user.orgRole.name, "via" to "sso"))
        }
        val sync = groupSync
        return if (groups != null && sync != null) sync.signedIn(user, groups) else user
    }

    suspend fun createUser(by: Principal, email: String, name: String, role: OrgRole): User {
        Access.requireAdmin(by)
        val normalized = email.trim().lowercase()
        if (!normalized.contains('@')) throw Invalid("A valid email is required")
        if (users.byEmail(normalized) != null) throw Conflict("A user with this email exists")
        return User(Ids.new(), normalized, name.ifBlank { normalized.substringBefore('@') }, role, createdAt = clock.now())
            .also {
                users.save(it)
                audit.record(AuditCategory.IDENTITY, "user.created", by.audit(), it.target(), mapOf("email" to it.email, "role" to it.orgRole.name))
            }
    }

    suspend fun updateUser(
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
        if (teamsUpdate != null) {
            for ((teamId, role) in teamsUpdate) {
                val t = teams.get(teamId) ?: throw Invalid("Unknown team $teamId")
                requireManualChange(t, u.teams[teamId], role)
            }
            for ((teamId, role) in u.teams) {
                if (teamId !in teamsUpdate) teams.get(teamId)?.let { requireManualChange(it, role, null) }
            }
        }
        if (by.user?.id == id && (role != null && role != OrgRole.ADMIN || disabled == true)) {
            throw Conflict("You cannot remove your own administrator access")
        }
        return u.copy(
            name = name ?: u.name,
            orgRole = role ?: u.orgRole,
            teams = teamsUpdate ?: u.teams,
            projectAdmin = projectAdmin ?: u.projectAdmin,
            disabled = disabled ?: u.disabled,
        ).also { saved ->
            users.save(saved)
            (u.teams.keys - saved.teams.keys).forEach { membership?.membershipRemoved(it, saved.id) }
            val changes = changes(u, saved)
            if (changes.isNotEmpty()) audit.record(AuditCategory.IDENTITY, "user.updated", by.audit(), saved.target(), changes, saved.teams.keys.toList())
        }
    }

    suspend fun teamMembers(by: Principal, teamId: String): List<TeamMember> {
        Access.requireRead(by)
        val t = teams.get(teamId) ?: teams.bySlug(teamId) ?: throw NotFound("Team")
        return users.byTeam(t.id).map { TeamMember(it.id, it.name, it.email, it.teams.getValue(t.id), t.membershipManaged || t.ownersManaged, it.disabled) }
            .sortedBy { it.name.lowercase() }
    }

    /** Team owners add, remove and promote members. [role] null removes the member. Groups of the identity provider win over manual changes. */
    suspend fun setTeamMember(by: Principal, teamId: String, userId: String, role: TeamRole?): User {
        val t = teams.get(teamId) ?: teams.bySlug(teamId) ?: throw NotFound("Team")
        if (!Access.isTeamAdmin(by, t.id, t.projectId)) throw Forbidden("Only team owners change the team members")
        val u = user(userId)
        requireManualChange(t, u.teams[t.id], role)
        val updated = u.copy(teams = if (role == null) u.teams - t.id else u.teams + (t.id to role))
        if (updated == u) return u
        users.save(updated)
        if (role == null && t.id in u.teams) membership?.membershipRemoved(t.id, u.id)
        val action = when { role == null -> "team_member.removed"; t.id !in u.teams -> "team_member.added"; else -> "team_member.role_changed" }
        audit.record(AuditCategory.IDENTITY, action, by.audit(), updated.target(),
            buildMap { put("team", t.id); u.teams[t.id]?.let { put("from", it.name) }; role?.let { put("to", it.name) } }, listOf(t.id))
        return updated
    }

    private fun requireManualChange(t: mayday.directory.Team, from: TeamRole?, to: TeamRole?) {
        if (from == to) return
        if (t.membershipManaged && (from == null || to == null)) throw Conflict("The identity provider manages the members of ${t.name}")
        if (t.ownersManaged && (from == TeamRole.ADMIN || to == TeamRole.ADMIN)) throw Conflict("The identity provider manages the owners of ${t.name}")
    }

    suspend fun issueToken(by: Principal, name: String, scopes: Set<TokenScope>, ttl: Duration?): IssuedToken {
        val owner = by.user ?: throw Forbidden("Only users can create personal tokens")
        if (by.isDelegated) throw Forbidden("Agents cannot create tokens for users")
        val allowed = scopes.filter { it !in Principal.ADMIN_ONLY || owner.orgRole == OrgRole.ADMIN }.toSet()
        return mint(OwnerKind.USER, owner.id, name, allowed.ifEmpty { setOf(TokenScope.READ) }, ttl).also {
            audit.record(AuditCategory.TOKEN, "token.issued", by.audit(), it.token.target(),
                it.token.auditDetails() + mapOf("requested" to scopes.lowercase()))
        }
    }

    suspend fun issueAgentToken(agentId: String, name: String, scopes: Set<TokenScope>, by: AuditActor = AuditActor.system): IssuedToken =
        mint(OwnerKind.AGENT, agentId, name, scopes, null).also {
            audit.record(AuditCategory.TOKEN, "token.issued", by, it.token.target(), it.token.auditDetails())
        }

    suspend fun tokensOf(kind: OwnerKind, ownerId: String) = tokens.byOwner(kind, ownerId)

    suspend fun revokeToken(by: Principal, id: String) {
        val t = tokens.get(id) ?: throw NotFound("Token")
        val own = t.ownerKind == OwnerKind.USER && t.ownerId == by.user?.id && !by.isDelegated
        if (!own) Access.requireAdmin(by)
        tokens.delete(id)
        audit.record(AuditCategory.TOKEN, "token.revoked", by.audit(), t.target(), t.auditDetails())
    }

    suspend fun revokeAgentTokens(agentId: String, by: AuditActor = AuditActor.system) = tokens.byOwner(OwnerKind.AGENT, agentId).forEach {
        tokens.delete(it.id)
        audit.record(AuditCategory.TOKEN, "token.revoked", by, it.target(), it.auditDetails())
    }

    private suspend fun mint(kind: OwnerKind, ownerId: String, name: String, scopes: Set<TokenScope>, ttl: Duration?): IssuedToken {
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
    suspend fun authenticate(bearer: String): Principal {
        if (bearer.startsWith("mdy_")) return authenticateApiToken(bearer)
        return authenticateDelegated(bearer) ?: throw Unauthenticated("Invalid token")
    }

    private suspend fun authenticateApiToken(secret: String): Principal {
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
    suspend fun delegate(user: User, agent: AgentIdentity, runId: String?, ttl: Duration, scopes: Set<TokenScope>): String =
        sign(user, agent, runId, ttl, scopes).also {
            audit.record(AuditCategory.TOKEN, "token.delegated", AuditActor(AuditParty("agent", agent.id, agent.name), user.auditParty(), runId = runId),
                AuditTarget("user", user.id, user.name), mapOf("scopes" to scopes.lowercase(), "expiresAt" to clock.now().plus(ttl).toString()))
        }

    private fun sign(user: User, agent: AgentIdentity, runId: String?, ttl: Duration, scopes: Set<TokenScope>): String {
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
    suspend fun exchange(subject: Principal, actor: Principal, ttl: Duration): String {
        val user = subject.user ?: throw Forbidden("The subject token must identify a user")
        if (subject.isAgent) throw Forbidden("The subject token must belong to a user")
        if (!actor.isAgent || actor.isDelegated) throw Forbidden("The actor token must belong to an agent")
        val agent = agents.find(actor.actor.id) ?: throw Forbidden("Unknown agent")
        val scopes = (if (agentAdminAllowed(user)) subject.scopes else subject.scopes - TokenScope.ADMIN) - TokenScope.SCIM
        return sign(user, agent, null, ttl, scopes).also {
            audit.record(AuditCategory.TOKEN, "token.exchanged", actor.audit().copy(onBehalfOf = user.auditParty()), AuditTarget("user", user.id, user.name),
                mapOf("scopes" to scopes.lowercase(), "adminKept" to (TokenScope.ADMIN in scopes).toString(), "expiresAt" to clock.now().plus(ttl).toString()))
        }
    }

    suspend fun agentAdminOrgAllowed() = policy.bool(Keys.AGENT_EXCHANGE_ADMIN, emptyList())

    /** When the organization stops allowing it, every opt-in ends. Administrators must opt in again after it is allowed again. */
    override suspend fun orgSettingChanged(key: String) {
        if (key != Keys.AGENT_EXCHANGE_ADMIN || agentAdminOrgAllowed()) return
        val cleared = users.withAgentAdmin()
        cleared.forEach { users.save(it.copy(allowAgentAdmin = false)) }
        if (cleared.isNotEmpty()) {
            audit.record(AuditCategory.IDENTITY, "agent_admin.cleared", AuditActor.system,
                details = mapOf("count" to cleared.size.toString(), "users" to cleared.joinToString(",") { it.id }))
        }
    }

    private suspend fun agentAdminAllowed(user: User) = user.allowAgentAdmin && agentAdminOrgAllowed()

    /** Only the person, in their own session or token, changes this. Agents never do, also not on behalf of the person. */
    suspend fun setAgentAdmin(by: Principal, allow: Boolean): User {
        val self = by.user ?: throw Forbidden("Only users have this setting")
        if (by.isAgent) throw Forbidden("Agents cannot change this setting")
        val u = user(self.id)
        if (allow) {
            if (!Access.isAdmin(by)) throw Forbidden("Only administrators can let agents keep the admin scope")
            if (!agentAdminOrgAllowed()) throw Forbidden("The organization does not allow admin rights through token exchange")
        }
        return u.copy(allowAgentAdmin = allow).also {
            users.save(it)
            if (u.allowAgentAdmin != allow) audit.record(AuditCategory.IDENTITY, if (allow) "agent_admin.enabled" else "agent_admin.disabled", by.audit(), it.target())
        }
    }

    private suspend fun authenticateDelegated(token: String): Principal? {
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
        if (run != null && !agents.runActive(run)) throw Unauthenticated("The run of this token is finished")
        return Principal(Actor(ActorKind.AGENT, agent.id, agent.name, onBehalfOf = user.id), user, agent.role, scopes, run)
    }
}
