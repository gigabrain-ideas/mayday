package mayday.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import mayday.Ports
import mayday.suspendTest
import mayday.agents.AgentAction
import mayday.agents.AgentRun
import mayday.agents.LlmConfig
import mayday.agents.RunStatus
import mayday.agents.RunTrigger
import mayday.agents.SubjectKind
import mayday.alerting.Alert
import mayday.alerting.AlertFilter
import mayday.alerting.AlertStatus
import mayday.directory.Team
import mayday.identity.ApiToken
import mayday.identity.OwnerKind
import mayday.identity.TokenScope
import mayday.identity.User
import mayday.incidents.EntryKind
import mayday.incidents.Incident
import mayday.incidents.IncidentFilter
import mayday.incidents.IncidentStatus
import mayday.incidents.TimelineEntry
import mayday.policy.Scope
import mayday.policy.SettingValue
import mayday.shared.Actor
import mayday.shared.Conflict
import mayday.shared.Ids
import mayday.shared.Severity
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract for every storage adapter. An adapter is correct when it passes this suite.
 * Subclasses provide fresh, empty ports for each test.
 */
abstract class StoreContract {
    protected abstract fun newPorts(): Ports
    protected lateinit var p: Ports
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @BeforeTest
    fun setUp() { p = newPorts() }

    private fun incident(number: Long, status: IncidentStatus = IncidentStatus.INVESTIGATING, sev: Severity = Severity.SEV2,
                         teams: List<String> = listOf("t1"), title: String = "Incident $number", at: Instant = now) =
        Incident(Ids.new(), number, "INC-$number", title, severity = sev, status = status, teamIds = teams, reportedAt = at,
            createdBy = Actor.system, createdAt = at, updatedAt = at)

    @Test
    fun `incident numbers increase monotonically and are never reused`() = suspendTest {
        val a = p.incidents.nextNumber(); val b = p.incidents.nextNumber(); val c = p.incidents.nextNumber()
        assertTrue(a < b && b < c)
    }

    @Test
    fun `incident round trip by id and number`() = suspendTest {
        val i = incident(p.incidents.nextNumber())
        p.incidents.save(i, null)
        assertEquals(i, p.incidents.get(i.id))
        assertEquals(i, p.incidents.byNumber(i.number))
        assertNull(p.incidents.get("missing"))
    }

    @Test
    fun `incident save enforces optimistic concurrency`() = suspendTest {
        val i = incident(p.incidents.nextNumber())
        p.incidents.save(i, null)
        assertFailsWith<Conflict> { p.incidents.save(i, null) }
        val v2 = i.copy(title = "changed", version = 2)
        p.incidents.save(v2, 1)
        assertFailsWith<Conflict> { p.incidents.save(i.copy(title = "stale", version = 2), 1) }
        assertEquals("changed", p.incidents.get(i.id)!!.title)
    }

    @Test
    fun `incident list is newest first, filtered and paginated`() = suspendTest {
        repeat(7) { n ->
            val num = p.incidents.nextNumber()
            p.incidents.save(incident(num, status = if (n % 2 == 0) IncidentStatus.RESOLVED else IncidentStatus.INVESTIGATING,
                sev = if (n < 3) Severity.SEV1 else Severity.SEV3, teams = listOf(if (n % 3 == 0) "a" else "b"), title = "Database $n"), null)
        }
        val first = p.incidents.list(IncidentFilter(), null, 3)
        assertEquals(3, first.items.size)
        assertNotNull(first.nextCursor)
        assertTrue(first.items.zipWithNext().all { (x, y) -> x.number > y.number })
        val second = p.incidents.list(IncidentFilter(), first.nextCursor, 3)
        assertTrue(second.items.first().number < first.items.last().number)
        val all = mutableListOf<Incident>()
        var cursor: String? = null
        do {
            val pg = p.incidents.list(IncidentFilter(), cursor, 2)
            all += pg.items
            cursor = pg.nextCursor
        } while (cursor != null)
        assertEquals(7, all.size)
        assertEquals(3, p.incidents.list(IncidentFilter(severities = setOf(Severity.SEV1)), null, 50).items.size)
        assertEquals(4, p.incidents.list(IncidentFilter(statuses = setOf(IncidentStatus.RESOLVED)), null, 50).items.size)
        assertEquals(3, p.incidents.list(IncidentFilter(teamIds = setOf("a")), null, 50).items.size)
        assertEquals(1, p.incidents.list(IncidentFilter(query = "database 4"), null, 50).items.size)
    }

    @Test
    fun `incident scan honours the time range`() = suspendTest {
        p.incidents.save(incident(p.incidents.nextNumber(), at = now.minus(10, ChronoUnit.DAYS)), null)
        p.incidents.save(incident(p.incidents.nextNumber(), at = now.minus(1, ChronoUnit.DAYS)), null)
        val seen = mutableListOf<Incident>()
        p.incidents.scan(IncidentFilter(from = now.minus(2, ChronoUnit.DAYS), to = now)) { seen += it }
        assertEquals(1, seen.size)
    }

    @Test
    fun `timeline is ordered by event time`() = suspendTest {
        val i = incident(p.incidents.nextNumber()); p.incidents.save(i, null)
        val late = TimelineEntry(Ids.new(), i.id, now, EntryKind.NOTE, Actor.system, "late")
        val early = TimelineEntry(Ids.new(), i.id, now.minusSeconds(60), EntryKind.NOTE, Actor.system, "early")
        p.timeline.append(late); p.timeline.append(early)
        assertEquals(listOf("early", "late"), p.timeline.list(i.id).map { it.text })
        assertTrue(p.timeline.list("other").isEmpty())
    }

    private fun alert(fp: String, at: Instant = now, team: String = "t1", sev: Severity = Severity.SEV3) =
        Alert(Ids.new(), fp, "test", "Alert $fp", severity = sev, teamId = team, firstSeenAt = at, lastSeenAt = at)

    @Test
    fun `alerts by fingerprint return the latest`() = suspendTest {
        val old = alert("fp", now.minusSeconds(3600)); val new = alert("fp", now)
        p.alerts.save(old, null); p.alerts.save(new, null)
        assertEquals(new.id, p.alerts.latestByFingerprint("fp")!!.id)
        assertNull(p.alerts.latestByFingerprint("none"))
    }

    @Test
    fun `alerts list, filter, paginate and enforce versions`() = suspendTest {
        val items = (0 until 5).map { alert("fp$it", now.minusSeconds(it * 10L), team = if (it < 2) "a" else "b") }
        items.forEach { p.alerts.save(it, null) }
        val pg = p.alerts.list(AlertFilter(), null, 2)
        assertEquals(listOf(items[0].id, items[1].id), pg.items.map { it.id })
        val pg2 = p.alerts.list(AlertFilter(), pg.nextCursor, 2)
        assertEquals(listOf(items[2].id, items[3].id), pg2.items.map { it.id })
        assertEquals(2, p.alerts.list(AlertFilter(teamIds = setOf("a")), null, 10).items.size)
        val acked = items[0].copy(status = AlertStatus.ACKNOWLEDGED, version = 2)
        p.alerts.save(acked, 1)
        assertFailsWith<Conflict> { p.alerts.save(acked.copy(version = 3), 1) }
        assertEquals(1, p.alerts.list(AlertFilter(statuses = setOf(AlertStatus.ACKNOWLEDGED)), null, 10).items.size)
    }

    @Test
    fun `users are unique by email and found by external id`() = suspendTest {
        val u = User(Ids.new(), "a@example.com", "A", externalId = "idp|1", createdAt = now)
        p.users.save(u)
        assertEquals(u, p.users.byEmail("a@example.com"))
        assertEquals(u, p.users.byExternalId("idp|1"))
        assertEquals(1, p.users.count())
        assertFailsWith<Conflict> { p.users.save(User(Ids.new(), "a@example.com", "B", createdAt = now)) }
        p.users.save(u.copy(name = "A2"))
        assertEquals("A2", p.users.get(u.id)!!.name)
    }

    @Test
    fun `users list pages by email`() = suspendTest {
        listOf("c", "a", "d", "b").forEach { p.users.save(User(Ids.new(), "$it@example.com", it.uppercase(), createdAt = now)) }
        val pg = p.users.list(null, null, 3)
        assertEquals(listOf("a@example.com", "b@example.com", "c@example.com"), pg.items.map { it.email })
        assertEquals(listOf("d@example.com"), p.users.list(null, pg.nextCursor, 3).items.map { it.email })
        assertEquals(1, p.users.list("B", null, 10).items.size)
    }

    @Test
    fun `tokens are found by hash and owner and can be deleted`() = suspendTest {
        val t = ApiToken(Ids.new(), "n", OwnerKind.AGENT, "agent1", "hash1", "mdy_", setOf(TokenScope.READ), now)
        p.tokens.save(t)
        assertEquals(t, p.tokens.byHash("hash1"))
        assertEquals(listOf(t), p.tokens.byOwner(OwnerKind.AGENT, "agent1"))
        p.tokens.delete(t.id)
        assertNull(p.tokens.byHash("hash1"))
    }

    @Test
    fun `settings upsert by scope and key`() = suspendTest {
        val v = SettingValue(Scope.TEAM, "t1", "k", "1", updatedBy = Actor.system, updatedAt = now)
        p.settings.save(v); p.settings.save(v.copy(value = "2"))
        assertEquals(listOf("2"), p.settings.all().map { it.value })
        p.settings.delete(v.id)
        assertTrue(p.settings.all().isEmpty())
    }

    @Test
    fun `teams are found by slug`() = suspendTest {
        val t = Team(Ids.new(), "sre", "SRE", createdAt = now)
        p.teams.save(t)
        assertEquals(t, p.teams.bySlug("sre"))
        p.teams.delete(t.id)
        assertNull(p.teams.get(t.id))
    }

    private fun run(agent: String, at: Instant) = AgentRun(Ids.new(), agent, "A", AgentAction.ANALYZE, SubjectKind.INCIDENT, "s1", "INC-1",
        trigger = RunTrigger.MANUAL, requestedBy = Actor.system, createdAt = at)

    @Test
    fun `claiming runs is atomic and oldest first`() = suspendTest {
        val r1 = run("a1", now.minusSeconds(30)); val r2 = run("a1", now.minusSeconds(20)); val other = run("a2", now)
        listOf(r1, r2, other).forEach { p.runs.save(it, null) }
        val claimed = p.runs.claim("a1", 1, now)
        assertEquals(listOf(r1.id), claimed.map { it.id })
        assertEquals(RunStatus.DISPATCHED, p.runs.get(r1.id)!!.status)
        assertEquals(listOf(r2.id), p.runs.claim("a1", 5, now).map { it.id })
        assertTrue(p.runs.claim("a1", 5, now).isEmpty())
        assertEquals(3, p.runs.active().size)
        assertEquals(3, p.runs.bySubject("s1").size)
    }

    @Test
    fun `concurrent claims never hand out a run twice`() = suspendTest {
        repeat(40) { p.runs.save(run("a1", now.minusSeconds(it.toLong())), null) }
        val got = java.util.concurrent.ConcurrentLinkedQueue<String>()
        (0 until 8).map { launch(Dispatchers.IO) { repeat(10) { p.runs.claim("a1", 3, now).forEach { got += it.id } } } }.joinAll()
        assertEquals(40, got.size)
        assertEquals(40, got.toSet().size)
    }

    @Test
    fun `llm config round trip`() = suspendTest {
        assertNull(p.config.llm())
        p.config.saveLlm(LlmConfig(model = "m", enabled = true))
        assertEquals("m", p.config.llm()!!.model)
    }
}
