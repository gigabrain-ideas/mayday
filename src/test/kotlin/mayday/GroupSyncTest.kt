package mayday

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import mayday.adapters.auth.OidcClient
import mayday.adapters.memory.MemoryStore
import mayday.identity.TeamRole
import mayday.identity.TokenScope
import mayday.oncall.ScheduleInput
import mayday.oncall.ShiftUnit
import mayday.provisioning.GroupSyncSettings
import mayday.provisioning.ProvisionedUser
import mayday.shared.Conflict
import mayday.shared.Forbidden
import mayday.shared.JSON
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun app(groups: GroupSyncSettings): App = App(
    AppSettings(secretKey = "test-secret", adminEmails = setOf("admin@example.com"), groups = groups),
    Ports.memory(MemoryStore(null)),
).also { runBlocking { it.start() } }

class GroupClaimTest {
    private fun claims(json: String) = JSON.parseToJsonElement(json).jsonObject

    @Test
    fun `the groups claim is read from a list, a nested path or a string`() {
        assertEquals(setOf("a", "b"), OidcClient.groups(claims("""{"groups":["a","b"," "]}"""), "groups"))
        assertEquals(setOf("ops"), OidcClient.groups(claims("""{"realm_access":{"roles":["ops"]}}"""), "realm_access.roles"))
        assertEquals(setOf("a", "b"), OidcClient.groups(claims("""{"groups":"a,b"}"""), "groups"))
        assertEquals(emptySet(), OidcClient.groups(claims("""{"groups":[]}"""), "groups"))
    }

    @Test
    fun `a missing claim means no groups because providers leave it out for users without groups`() {
        assertEquals(emptySet(), OidcClient.groups(claims("""{"sub":"x"}"""), "groups"))
        assertEquals(emptySet(), OidcClient.groups(claims("""{"realm_access":{}}"""), "realm_access.roles"))
    }

    @Test
    fun `a blank claim name or a group overage means unknown groups, not no groups`() {
        assertNull(OidcClient.groups(claims("""{"groups":["a"]}"""), " "))
        assertNull(OidcClient.groups(claims("""{"_claim_names":{"groups":"src1"},"_claim_sources":{"src1":{"endpoint":"https://graph"}}}"""), "groups"))
    }
}

class GroupSyncTest {
    @Test
    fun `sign-in groups create teams and keep the membership equal to the groups`() = suspendTest {
        val a = app(GroupSyncSettings(autoCreate = true, filter = "^oncall-", ownerSuffix = "-owners"))
        val ana = a.identity.upsertSso("idp|ana", "ana@example.com", "Ana", setOf("oncall-payments", "oncall-payments-owners", "staff"))
        val team = a.directory.teams().single()
        assertEquals("oncall-payments", team.name)
        assertEquals(setOf("oncall-payments"), team.memberGroups)
        assertEquals(setOf("oncall-payments-owners"), team.ownerGroups)
        assertEquals(TeamRole.ADMIN, ana.teams[team.id])

        val ben = a.identity.upsertSso("idp|ben", "ben@example.com", "Ben", setOf("oncall-payments"))
        assertEquals(TeamRole.MEMBER, ben.teams[team.id])

        val anaAgain = a.identity.upsertSso("idp|ana", "ana@example.com", "Ana", setOf("oncall-payments"))
        assertEquals(TeamRole.MEMBER, anaAgain.teams[team.id])
        val anaGone = a.identity.upsertSso("idp|ana", "ana@example.com", "Ana", emptySet())
        assertNull(anaGone.teams[team.id])
    }

    @Test
    fun `unknown groups keep the membership and groups outside the filter create no team`() = suspendTest {
        val a = app(GroupSyncSettings(autoCreate = true, filter = "^oncall-"))
        val u = a.identity.upsertSso("idp|u", "u@example.com", "U", setOf("oncall-sre", "all-staff"))
        val team = a.directory.teams().single()
        assertEquals("oncall-sre", team.slug)
        val same = a.identity.upsertSso("idp|u", "u@example.com", "U", null)
        assertEquals(u.teams, same.teams)
    }

    @Test
    fun `without auto-create only teams that an administrator linked follow the groups`() = suspendTest {
        val a = app(GroupSyncSettings())
        val admin = a.identity.principalForUser(a.identity.upsertSso("idp|admin", "admin@example.com", "Admin"))
        a.identity.upsertSso("idp|u", "u@example.com", "U", setOf("8f1c-guid"))
        assertTrue(a.directory.teams().isEmpty())
        val team = a.directory.createTeam("SRE", "sre", null)
        a.provisioning.linkTeam(admin, team.id, setOf("8f1c-guid"), emptySet())
        assertEquals(TeamRole.MEMBER, a.ports.users.byEmail("u@example.com")!!.teams[team.id])
        a.provisioning.linkTeam(admin, team.id, emptySet(), emptySet())
        assertEquals(TeamRole.MEMBER, a.ports.users.byEmail("u@example.com")!!.teams[team.id], "unlinking keeps the members as manual members")
    }

    @Test
    fun `manual changes to members or owners that groups manage are a conflict`() = suspendTest {
        val a = app(GroupSyncSettings())
        val admin = a.identity.principalForUser(a.identity.upsertSso("idp|admin", "admin@example.com", "Admin"))
        val u = a.identity.upsertSso("idp|u", "u@example.com", "U", setOf("members"))
        val v = a.identity.upsertSso("idp|v", "v@example.com", "V")
        val team = a.directory.createTeam("SRE", "sre", null)
        a.provisioning.linkTeam(admin, team.id, setOf("members"), emptySet())
        assertFailsWith<Conflict> { a.identity.setTeamMember(admin, team.id, v.id, TeamRole.MEMBER) }
        assertFailsWith<Conflict> { a.identity.setTeamMember(admin, team.id, u.id, null) }
        a.identity.setTeamMember(admin, team.id, u.id, TeamRole.ADMIN)
        a.provisioning.linkTeam(admin, team.id, setOf("members"), setOf("owners"))
        assertEquals(TeamRole.MEMBER, a.ports.users.get(u.id)!!.teams[team.id])
        assertFailsWith<Conflict> { a.identity.setTeamMember(admin, team.id, u.id, TeamRole.ADMIN) }
        assertFailsWith<Conflict> { a.identity.updateUser(admin, v.id, teamsUpdate = mapOf(team.id to TeamRole.MEMBER)) }
    }

    @Test
    fun `losing the group removes the person from on-call rotations`() = suspendTest {
        val a = app(GroupSyncSettings(autoCreate = true))
        val admin = a.identity.principalForUser(a.identity.upsertSso("idp|admin", "admin@example.com", "Admin"))
        val u = a.identity.upsertSso("idp|u", "u@example.com", "U", setOf("sre"))
        val team = a.directory.teams().single()
        val s = a.onCall.create(admin, ScheduleInput(team.id, "Primary", rotationStart = Instant.now(), shiftUnit = ShiftUnit.DAYS, participants = listOf(u.id)))
        a.identity.upsertSso("idp|u", "u@example.com", "U", emptySet())
        assertTrue(a.onCall.schedule(admin, s.id).participants.isEmpty())
    }
}

class ProvisioningTest {
    private val a = app(GroupSyncSettings(autoCreate = true))
    private val admin = runBlocking { a.identity.upsertSso("idp|admin", "admin@example.com", "Admin") }
    private val scim = runBlocking {
        val t = a.identity.issueToken(a.identity.principalForUser(admin), "scim", setOf(TokenScope.SCIM), null)
        a.identity.authenticate(t.secret)
    }

    @Test
    fun `SCIM groups become teams with a readable name and SCIM membership drives team membership`() = suspendTest {
        val ana = a.provisioning.createUser(scim, ProvisionedUser("ana@example.com", "Ana", externalId = "e-ana"))
        val g = a.provisioning.createGroup(scim, "Payments On-call", "0b5e-guid", setOf(ana.id))
        val team = a.directory.teams().single()
        assertEquals("Payments On-call", team.name)
        assertEquals(setOf("0b5e-guid"), team.memberGroups)
        assertEquals(TeamRole.MEMBER, a.ports.users.get(ana.id)!!.teams[team.id])
        a.provisioning.changeMembers(scim, g.id, remove = setOf(ana.id))
        assertNull(a.ports.users.get(ana.id)!!.teams[team.id])
    }

    @Test
    fun `a team made from a sign-in group id gets the SCIM display name later`() = suspendTest {
        a.identity.upsertSso("idp|u", "u@example.com", "U", setOf("77aa-guid"))
        assertEquals("77aa-guid", a.directory.teams().single().name)
        a.provisioning.createGroup(scim, "Identity", "77aa-guid", emptySet())
        assertEquals("Identity", a.directory.teams().single().name)
    }

    @Test
    fun `a changed group key moves the members and the team links`() = suspendTest {
        val ana = a.provisioning.createUser(scim, ProvisionedUser("ana@example.com", "Ana"))
        val g = a.provisioning.createGroup(scim, "SRE", null, setOf(ana.id))
        a.provisioning.updateGroup(scim, g.id, "SRE", "new-guid")
        val team = a.directory.teams().single()
        assertEquals(setOf("new-guid"), team.memberGroups)
        assertEquals(setOf("new-guid"), a.ports.users.get(ana.id)!!.scimGroups)
        assertNotNull(a.ports.users.get(ana.id)!!.teams[team.id])
    }

    @Test
    fun `deprovisioning disables the user and removes their group memberships`() = suspendTest {
        val ana = a.provisioning.createUser(scim, ProvisionedUser("ana@example.com", "Ana"))
        a.provisioning.createGroup(scim, "SRE", null, setOf(ana.id))
        a.provisioning.deleteUser(scim, ana.id)
        val after = a.ports.users.get(ana.id)!!
        assertTrue(after.disabled)
        assertTrue(after.teams.isEmpty())
    }

    @Test
    fun `only an administrator token with the SCIM scope provisions`() = suspendTest {
        val full = a.identity.principalForUser(admin, setOf(TokenScope.READ, TokenScope.WRITE, TokenScope.ADMIN))
        assertFailsWith<Forbidden> { a.provisioning.createUser(full, ProvisionedUser("x@example.com", "X")) }
        val member = a.identity.upsertSso("idp|m", "m@example.com", "M")
        val issued = a.identity.issueToken(a.identity.principalForUser(member), "scim", setOf(TokenScope.SCIM), null)
        assertTrue(TokenScope.SCIM !in issued.token.scopes, "members cannot get the SCIM scope")
        assertFailsWith<Conflict> { a.provisioning.createUser(scim, ProvisionedUser("admin@example.com", "Again")) }
        assertFailsWith<Conflict> { a.provisioning.updateUser(scim, admin.id, ProvisionedUser("admin@example.com", "Admin", active = false)) }
    }
}
