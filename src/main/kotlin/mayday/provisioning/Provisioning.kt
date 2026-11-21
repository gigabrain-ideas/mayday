package mayday.provisioning

import kotlinx.serialization.Serializable
import mayday.directory.DirectoryService
import mayday.directory.Team
import mayday.identity.Access
import mayday.identity.GroupMembershipSync
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.identity.TeamMembershipListener
import mayday.identity.TeamRole
import mayday.identity.TokenScope
import mayday.identity.User
import mayday.identity.UserRepository
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Ts

/** A group that a SCIM client provisioned. [key] links it to teams: the externalId (Entra: object id) or else the display name. */
@Serializable
data class DirectoryGroup(
    val id: String,
    val displayName: String,
    val externalId: String? = null,
    val createdAt: Ts,
    val updatedAt: Ts,
) {
    val key: String get() = externalId?.takeIf { it.isNotBlank() } ?: displayName
}

interface GroupRepository {
    suspend fun get(id: String): DirectoryGroup?
    suspend fun list(): List<DirectoryGroup>
    suspend fun save(group: DirectoryGroup)
    suspend fun delete(id: String)
}

/**
 * [autoCreate]: a group that no team links to creates a team, when [filter] (a regular expression) matches its key or name.
 * [ownerSuffix]: a group named "<base><suffix>" becomes the owner group of the team of group "<base>".
 */
data class GroupSyncSettings(val autoCreate: Boolean = false, val filter: String = "", val ownerSuffix: String = "") {
    private val regex: Regex? = filter.trim().takeIf { it.isNotEmpty() }?.let { Regex(it) }
    fun matches(key: String, name: String?) = regex == null || regex.containsMatchIn(key) || (name != null && regex.containsMatchIn(name))
}

/** Keeps team membership equal to the groups of the identity provider, from sign-in claims and from SCIM. */
class GroupSync(
    private val users: UserRepository,
    private val directory: DirectoryService,
    private val settings: GroupSyncSettings,
    private val membership: () -> TeamMembershipListener?,
) : GroupMembershipSync {

    override suspend fun signedIn(user: User, groups: Set<String>): User {
        if (user.idpGroups == groups) return user
        ensureTeams(groups.associateWith { null })
        val u = if (user.idpGroups == groups) user else user.copy(idpGroups = groups).also { users.save(it) }
        return reconcile(u, directory.teamsWithGroups())
    }

    /** Creates or names teams for groups that no team links to yet. [groups] maps a key to its display name, when known. */
    suspend fun ensureTeams(groups: Map<String, String?>) {
        if (groups.isEmpty()) return
        var linked = directory.teamsWithGroups()
        for ((key, name) in groups) {
            val team = linked.firstOrNull { key in it.groups }
            if (team != null) {
                if (name != null && name != key && team.name == key) directory.renameTeam(team.id, name)
                continue
            }
            if (!settings.autoCreate || !settings.matches(key, name)) continue
            val suffix = settings.ownerSuffix
            if (suffix.isNotEmpty() && key.endsWith(suffix) && key.length > suffix.length) {
                val base = key.removeSuffix(suffix)
                val baseTeam = linked.firstOrNull { base in it.memberGroups }
                if (baseTeam != null) directory.setTeamGroups(baseTeam.id, baseTeam.memberGroups, baseTeam.ownerGroups + key)
                else create(base, setOf(base), setOf(key))
            } else {
                create(name ?: key, setOf(key), emptySet())
            }
            linked = directory.teamsWithGroups()
        }
    }

    private suspend fun create(name: String, members: Set<String>, owners: Set<String>) =
        directory.createTeam(name, directory.freeSlug(name), null, "Created from identity provider groups", members, owners)

    suspend fun reconcile(user: User, teams: List<Team>): User {
        val next = desired(user, teams)
        if (next == user.teams) return user
        val u = user.copy(teams = next)
        users.save(u)
        (user.teams.keys - next.keys).forEach { membership()?.membershipRemoved(it, u.id) }
        return u
    }

    suspend fun reconcileGroups(keys: Set<String>) {
        if (keys.isEmpty()) return
        val teams = directory.teamsWithGroups()
        users.withGroups(keys).forEach { reconcile(it, teams) }
    }

    suspend fun teamGroupsChanged(team: Team, before: Set<String>) {
        val teams = directory.teamsWithGroups()
        val affected = (users.byTeam(team.id) + users.withGroups(before + team.groups)).map { it.id }.toSet()
        affected.forEach { id -> users.get(id)?.let { reconcile(it, teams) } }
    }

    companion object {
        /** Team roles that the groups of [user] give. Teams without links keep their manual membership. */
        fun desired(user: User, teams: List<Team>): Map<String, TeamRole> {
            val groups = user.groups
            val out = user.teams.toMutableMap()
            for (t in teams) {
                val owner = t.ownerGroups.any { it in groups }
                val member = owner || t.memberGroups.any { it in groups }
                if (t.membershipManaged) {
                    if (!member) out.remove(t.id)
                    else out[t.id] = when {
                        t.ownersManaged -> if (owner) TeamRole.ADMIN else TeamRole.MEMBER
                        else -> out[t.id] ?: TeamRole.MEMBER
                    }
                } else if (t.ownersManaged) {
                    if (owner) out[t.id] = TeamRole.ADMIN
                    else if (out[t.id] == TeamRole.ADMIN) out[t.id] = TeamRole.MEMBER
                }
            }
            return out
        }
    }
}

/** User attributes that a SCIM client sets. */
data class ProvisionedUser(
    val email: String,
    val name: String,
    val active: Boolean = true,
    val externalId: String? = null,
)

data class Listing<T>(val items: List<T>, val total: Long)

/** Use cases behind the SCIM 2.0 API (RFC 7644). Only an administrator's token with the SCIM scope calls them. */
class ProvisioningService(
    private val users: UserRepository,
    private val groups: GroupRepository,
    private val directory: DirectoryService,
    private val sync: GroupSync,
    private val clock: Clock,
    private val bootstrapAdmins: Set<String>,
) {
    fun requireScim(by: Principal) {
        if (by.isAgent || !by.has(TokenScope.SCIM) || by.role != OrgRole.ADMIN) throw Forbidden("SCIM needs an administrator token with the SCIM scope")
    }

    // ---- users ----

    suspend fun users(by: Principal, attribute: String?, value: String?, startIndex: Int, count: Int): Listing<User> {
        requireScim(by)
        if (attribute != null) {
            val u = when (attribute.lowercase()) {
                "username", "emails", "emails.value" -> users.byEmail(value.orEmpty().trim().lowercase())
                "externalid" -> users.byScimExternalId(value.orEmpty())
                "id" -> users.get(value.orEmpty())
                else -> throw Invalid("Filtering by '$attribute' is not supported")
            }
            return Listing(listOfNotNull(u), if (u == null) 0 else 1)
        }
        val wanted = startIndex - 1 + count
        val out = ArrayList<User>()
        var cursor: String? = null
        do {
            val page = users.list(null, cursor, 500)
            out += page.items
            cursor = page.nextCursor
        } while (cursor != null && out.size < wanted)
        return Listing(out.drop(startIndex - 1).take(count), users.count())
    }

    suspend fun user(by: Principal, id: String): User {
        requireScim(by)
        return users.get(id) ?: throw NotFound("User")
    }

    suspend fun createUser(by: Principal, input: ProvisionedUser): User {
        requireScim(by)
        val email = email(input.email)
        if (users.byEmail(email) != null) throw Conflict("A user with this email exists")
        val role = if (email in bootstrapAdmins) OrgRole.ADMIN else OrgRole.MEMBER
        val u = User(Ids.new(), email, input.name.ifBlank { email.substringBefore('@') }, role, disabled = !input.active,
            createdAt = clock.now(), scimExternalId = input.externalId?.ifBlank { null })
        users.save(u)
        return u
    }

    suspend fun updateUser(by: Principal, id: String, input: ProvisionedUser): User {
        requireScim(by)
        val u = users.get(id) ?: throw NotFound("User")
        if (u.id == by.user?.id && !input.active) throw Conflict("You cannot deactivate the owner of this SCIM token")
        val next = u.copy(email = email(input.email), name = input.name.ifBlank { u.name }, disabled = !input.active,
            scimExternalId = input.externalId?.ifBlank { null } ?: u.scimExternalId)
        if (next != u) users.save(next)
        return next
    }

    /** The user stays for the history of incidents. Mayday disables them and removes their SCIM groups. */
    suspend fun deleteUser(by: Principal, id: String) {
        requireScim(by)
        val u = users.get(id) ?: throw NotFound("User")
        if (u.id == by.user?.id) throw Conflict("You cannot delete the owner of this SCIM token")
        val next = u.copy(disabled = true, scimGroups = emptySet())
        users.save(next)
        sync.reconcile(next, directory.teamsWithGroups())
    }

    // ---- groups ----

    suspend fun groups(by: Principal, attribute: String?, value: String?, startIndex: Int, count: Int): Listing<DirectoryGroup> {
        requireScim(by)
        val all = groups.list().sortedBy { it.displayName.lowercase() }
        val filtered = when (attribute?.lowercase()) {
            null -> all
            "displayname" -> all.filter { it.displayName == value }
            "externalid" -> all.filter { it.externalId == value }
            "id" -> all.filter { it.id == value }
            else -> throw Invalid("Filtering by '$attribute' is not supported")
        }
        return Listing(filtered.drop(startIndex - 1).take(count), filtered.size.toLong())
    }

    suspend fun group(by: Principal, id: String): DirectoryGroup {
        requireScim(by)
        return groups.get(id) ?: throw NotFound("Group")
    }

    suspend fun members(group: DirectoryGroup): List<User> = users.withGroups(setOf(group.key)).filter { group.key in it.scimGroups }

    suspend fun createGroup(by: Principal, displayName: String, externalId: String?, memberIds: Set<String>): DirectoryGroup {
        requireScim(by)
        if (displayName.isBlank()) throw Invalid("displayName is required")
        val now = clock.now()
        val g = DirectoryGroup(Ids.new(), displayName.trim(), externalId?.trim()?.ifEmpty { null }, now, now)
        if (groups.list().any { it.key == g.key }) throw Conflict("A group with this key exists")
        groups.save(g)
        sync.ensureTeams(mapOf(g.key to g.displayName))
        setMembers(g, memberIds)
        return g
    }

    /** Changes name and externalId. When the key changes, users and team links move to the new key. */
    suspend fun updateGroup(by: Principal, id: String, displayName: String, externalId: String?): DirectoryGroup {
        requireScim(by)
        val g = groups.get(id) ?: throw NotFound("Group")
        if (displayName.isBlank()) throw Invalid("displayName is required")
        val next = g.copy(displayName = displayName.trim(), externalId = externalId?.trim()?.ifEmpty { null }, updatedAt = clock.now())
        if (next.key != g.key) {
            if (groups.list().any { it.id != g.id && it.key == next.key }) throw Conflict("A group with this key exists")
            for (u in members(g)) users.save(u.copy(scimGroups = u.scimGroups - g.key + next.key))
            for (t in directory.teamsWithGroups().filter { g.key in it.groups }) {
                directory.setTeamGroups(t.id, t.memberGroups.swap(g.key, next.key), t.ownerGroups.swap(g.key, next.key))
            }
        }
        groups.save(next)
        sync.ensureTeams(mapOf(next.key to next.displayName))
        return next
    }

    suspend fun changeMembers(by: Principal, id: String, add: Set<String> = emptySet(), remove: Set<String> = emptySet(), replace: Set<String>? = null) {
        requireScim(by)
        val g = groups.get(id) ?: throw NotFound("Group")
        val current = members(g).map { it.id }.toSet()
        setMembers(g, (replace ?: current) + add - remove)
    }

    suspend fun deleteGroup(by: Principal, id: String) {
        requireScim(by)
        val g = groups.get(id) ?: throw NotFound("Group")
        setMembers(g, emptySet())
        groups.delete(g.id)
    }

    private suspend fun setMembers(g: DirectoryGroup, wanted: Set<String>) {
        val current = members(g).associateBy { it.id }
        val changed = ArrayList<User>()
        for (uid in wanted - current.keys) {
            val u = users.get(uid) ?: throw Invalid("Unknown member $uid")
            changed += u.copy(scimGroups = u.scimGroups + g.key).also { users.save(it) }
        }
        for ((uid, u) in current) {
            if (uid !in wanted) changed += u.copy(scimGroups = u.scimGroups - g.key).also { users.save(it) }
        }
        if (changed.isEmpty()) return
        val teams = directory.teamsWithGroups()
        changed.forEach { sync.reconcile(it, teams) }
    }

    // ---- team links (administrators, from the web interface or gRPC) ----

    suspend fun linkTeam(by: Principal, teamId: String, memberGroups: Set<String>, ownerGroups: Set<String>): Team {
        Access.requireAdmin(by)
        val before = directory.team(teamId)
        val after = directory.setTeamGroups(before.id, memberGroups, ownerGroups)
        sync.teamGroupsChanged(after, before.groups)
        return after
    }

    private fun email(raw: String): String {
        val e = raw.trim().lowercase()
        if (!e.contains('@')) throw Invalid("userName or a primary email must be an email address")
        return e
    }

    private fun Set<String>.swap(old: String, new: String) = if (old in this) this - old + new else this
}
