package mayday.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.test.AfterTest
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

    @AfterTest
    fun tearDown() = p.close()

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
        val old = alert("fp", now.minusSeconds(3600)).copy(status = AlertStatus.RESOLVED); val new = alert("fp", now)
        p.alerts.save(old, null); p.alerts.save(new, null)
        assertEquals(new.id, p.alerts.latestByFingerprint("t1", "fp")!!.id)
        assertNull(p.alerts.latestByFingerprint("t1", "none"))
        p.alerts.save(old.copy(count = 2, version = 2), 1)
        assertEquals(new.id, p.alerts.latestByFingerprint("t1", "fp")!!.id, "an update of an older alert keeps the latest")
    }

    /** Alertmanager retries and many replicas send one signal several times at once. */
    @Test
    fun `concurrent signals with one fingerprint make one alert and one incident`() = suspendTest {
        val app = mayday.App(mayday.AppSettings(secretKey = "store-contract"), p)
        val team = app.directory.createTeam("Ops", "ops-${Ids.new().take(6)}", null)
        val signal = mayday.alerting.AlertSignal("Disk full", Severity.SEV1, team.id, fingerprint = "race")
        val results = (1..8).map { async(Dispatchers.IO) { runCatching { app.alerts.ingest(mayday.identity.Principal.system, signal) } } }.awaitAll()
        results.forEach { it.getOrThrow() }
        assertEquals(1, results.count { it.getOrThrow().created })
        val alerts = p.alerts.list(AlertFilter(teamIds = setOf(team.id)), null, 50).items
        assertEquals(1, alerts.size)
        assertEquals(8L, alerts.single().count)
        assertEquals(1, p.incidents.list(IncidentFilter(teamIds = setOf(team.id)), null, 50).items.size)
    }

    @Test
    fun `a team has at most one open alert for a fingerprint`() = suspendTest {
        val open = alert("one", team = "a")
        p.alerts.save(open, null)
        assertFailsWith<Conflict> { p.alerts.save(alert("one", now.plusSeconds(1), team = "a"), null) }
        p.alerts.save(alert("one", team = "b"), null)
        p.alerts.save(alert("one", now.minusSeconds(60), team = "a").copy(status = AlertStatus.RESOLVED), null)
        p.alerts.save(open.copy(status = AlertStatus.RESOLVED, version = 2), 1)
        p.alerts.save(alert("one", now.plusSeconds(2), team = "a"), null)
    }

    @Test
    fun `alert counts group by team and UTC day with the same filter as the list`() = suspendTest {
        val day = Instant.parse("2026-03-10T12:00:00Z")
        listOf(
            alert("s1", day, team = "a"), alert("s2", day.plusSeconds(3600), team = "a").copy(incidentId = "i1"),
            alert("s3", day.plusSeconds(13 * 3600), team = "a"), alert("s4", day, team = "b", sev = Severity.SEV1),
            alert("s5", day.minusSeconds(86_400 * 30), team = "a"),
        ).forEach { p.alerts.save(it, null) }
        val counts = p.alerts.counts(AlertFilter(from = day.minusSeconds(86_400), to = day.plusSeconds(86_400)))
        assertEquals(setOf(
            mayday.alerting.AlertCount("a", java.time.LocalDate.parse("2026-03-10"), 2, 1),
            mayday.alerting.AlertCount("a", java.time.LocalDate.parse("2026-03-11"), 1, 0),
            mayday.alerting.AlertCount("b", java.time.LocalDate.parse("2026-03-10"), 1, 0),
        ), counts.toSet())
        assertEquals(listOf(mayday.alerting.AlertCount("b", java.time.LocalDate.parse("2026-03-10"), 1, 0)),
            p.alerts.counts(AlertFilter(severities = setOf(Severity.SEV1), teamIds = setOf("b"))))
    }

    @Test
    fun `alerts by fingerprint are found per team`() = suspendTest {
        val a = alert("fp", team = "a"); val b = alert("fp", now.minusSeconds(60), team = "b")
        p.alerts.save(a, null); p.alerts.save(b, null)
        assertEquals(a.id, p.alerts.latestByFingerprint("a", "fp")!!.id)
        assertEquals(b.id, p.alerts.latestByFingerprint("b", "fp")!!.id)
        assertNull(p.alerts.latestByFingerprint("c", "fp"))
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
    fun `alerts count exactly with the filter, beyond one page`() = suspendTest {
        val firing = (0 until 130).map { alert("c$it", now.minusSeconds(it.toLong()), team = if (it % 2 == 0) "a" else "b") }
        firing.forEach { p.alerts.save(it, null) }
        listOf(AlertStatus.ACKNOWLEDGED, AlertStatus.RESOLVED).forEach { s -> p.alerts.save(alert("x$s", now).copy(status = s), null) }
        assertEquals(130, p.alerts.count(AlertFilter(statuses = setOf(AlertStatus.FIRING))))
        assertEquals(65, p.alerts.count(AlertFilter(statuses = setOf(AlertStatus.FIRING), teamIds = setOf("a"))))
        assertEquals(131, p.alerts.count(AlertFilter(statuses = setOf(AlertStatus.FIRING, AlertStatus.ACKNOWLEDGED))))
        assertEquals(132, p.alerts.count(AlertFilter()))
        assertEquals(0, p.alerts.count(AlertFilter(teamIds = setOf("none"))))
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
    fun `users with the agent admin opt-in are found`() = suspendTest {
        val on = User(Ids.new(), "on@example.com", "On", createdAt = now, allowAgentAdmin = true)
        p.users.save(on)
        p.users.save(User(Ids.new(), "off@example.com", "Off", createdAt = now))
        assertEquals(listOf(on), p.users.withAgentAdmin())
        p.users.save(on.copy(allowAgentAdmin = false))
        assertTrue(p.users.withAgentAdmin().isEmpty())
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
    fun `runs that wait for approval or are finished are not active`() = suspendTest {
        val working = run("a1", now); val waiting = run("a1", now).copy(status = RunStatus.AWAITING_APPROVAL, requiresApproval = true)
        val done = run("a1", now).copy(status = RunStatus.REJECTED)
        listOf(working, waiting, done).forEach { p.runs.save(it, null) }
        assertEquals(listOf(working.id), p.runs.active().map { it.id })
        assertEquals(RunStatus.AWAITING_APPROVAL, p.runs.get(waiting.id)!!.status)
        assertTrue(p.runs.get(waiting.id)!!.requiresApproval)
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

    // ---- on-call ----

    private fun schedule(team: String = "t1") = mayday.oncall.Schedule(Ids.new(), team, "Primary", "Europe/Berlin", now, 1, mayday.oncall.ShiftUnit.WEEKS,
        listOf("u1", "u2"), createdAt = now, updatedAt = now)

    @Test
    fun `schedules round trip by team and enforce optimistic concurrency`() = suspendTest {
        val s = schedule()
        p.schedules.save(s, null)
        p.schedules.save(schedule("t2"), null)
        assertEquals(s, p.schedules.get(s.id))
        assertEquals(listOf(s), p.schedules.byTeam("t1"))
        assertEquals(2, p.schedules.list().size)
        assertFailsWith<Conflict> { p.schedules.save(s, null) }
        val v2 = s.copy(name = "Secondary", version = 2)
        p.schedules.save(v2, 1)
        assertFailsWith<Conflict> { p.schedules.save(s.copy(name = "stale", version = 2), 1) }
        assertEquals("Secondary", p.schedules.get(s.id)!!.name)
        p.schedules.delete(s.id)
        assertNull(p.schedules.get(s.id))
    }

    @Test
    fun `overrides are found by overlap in creation order and by person`() = suspendTest {
        fun o(start: Long, end: Long, created: Long, user: String = "u1", schedule: String = "s1") = mayday.oncall.ScheduleOverride(Ids.new(), schedule, "t1", user,
            now.plus(start, ChronoUnit.HOURS), now.plus(end, ChronoUnit.HOURS), "sick", "u9", now.plusSeconds(created))
        val late = o(0, 10, 2); val early = o(5, 8, 1); val outside = o(20, 30, 0); val other = o(0, 10, 0, schedule = "s2"); val bob = o(0, 3, 3, user = "u2")
        listOf(late, early, outside, other, bob).forEach { p.overrides.save(it) }
        assertEquals(listOf(early, late, bob), p.overrides.overlapping("s1", now.plus(1, ChronoUnit.HOURS), now.plus(9, ChronoUnit.HOURS)))
        assertEquals(listOf(early, late), p.overrides.overlapping("s1", now.plus(5, ChronoUnit.HOURS), now.plus(6, ChronoUnit.HOURS)))
        assertTrue(p.overrides.overlapping("s1", now.plus(10, ChronoUnit.HOURS), now.plus(20, ChronoUnit.HOURS)).isEmpty(), "end and start are exclusive")
        assertEquals(setOf(outside.id), p.overrides.endingAfter("t1", "u1", now.plus(12, ChronoUnit.HOURS)).map { it.id }.toSet())
        p.overrides.save(late.copy(endsAt = now.plus(1, ChronoUnit.HOURS)))
        assertEquals(now.plus(1, ChronoUnit.HOURS), p.overrides.get(late.id)!!.endsAt)
        p.overrides.delete(early.id)
        assertNull(p.overrides.get(early.id))
        p.overrides.deleteBySchedule("s1")
        assertNull(p.overrides.get(late.id))
        assertNotNull(p.overrides.get(other.id))
    }

    // ---- teams and groups ----

    @Test
    fun `users are found by team, by group and by SCIM external id`() = suspendTest {
        val a = User(Ids.new(), "a@example.com", "A", teams = mapOf("t1" to mayday.identity.TeamRole.ADMIN), idpGroups = setOf("g1"), createdAt = now)
        val b = User(Ids.new(), "b@example.com", "B", teams = mapOf("t2" to mayday.identity.TeamRole.MEMBER), scimGroups = setOf("g2"), scimExternalId = "ext-b", createdAt = now)
        val c = User(Ids.new(), "c@example.com", "C", createdAt = now)
        listOf(a, b, c).forEach { p.users.save(it) }
        assertEquals(listOf(a), p.users.byTeam("t1"))
        assertEquals(setOf(a.id, b.id), p.users.withGroups(setOf("g1", "g2", "g3")).map { it.id }.toSet())
        assertEquals(listOf(b), p.users.withGroups(setOf("g2")))
        assertTrue(p.users.withGroups(emptySet()).isEmpty())
        assertEquals(b, p.users.byScimExternalId("ext-b"))
        assertNull(p.users.byScimExternalId("missing"))
        p.users.save(a.copy(teams = emptyMap(), idpGroups = emptySet()))
        assertTrue(p.users.byTeam("t1").isEmpty())
        assertTrue(p.users.withGroups(setOf("g1")).isEmpty())
    }

    @Test
    fun `teams keep their group links and directory groups round trip`() = suspendTest {
        val t = Team(Ids.new(), "sre", "SRE", createdAt = now, memberGroups = setOf("g1"), ownerGroups = setOf("g1-owners"))
        p.teams.save(t)
        assertEquals(t, p.teams.get(t.id))
        val g = mayday.provisioning.DirectoryGroup(Ids.new(), "SRE", "guid-1", now, now)
        p.groups.save(g)
        assertEquals(g, p.groups.get(g.id))
        assertEquals(listOf(g), p.groups.list())
        p.groups.delete(g.id)
        assertNull(p.groups.get(g.id))
    }

    // ---- escalation ----

    private fun policy(team: String = "t1") = mayday.escalation.EscalationPolicy(Ids.new(), team, "Default", "d",
        listOf(mayday.escalation.EscalationLevel(5, listOf(mayday.escalation.EscalationTarget(mayday.escalation.TargetKind.SCHEDULE, "s1"),
            mayday.escalation.EscalationTarget(mayday.escalation.TargetKind.USER, "u1")))), 1, true, now, now)

    @Test
    fun `escalation policies round trip by team and enforce optimistic concurrency`() = suspendTest {
        val a = policy()
        p.escalationPolicies.save(a, null)
        p.escalationPolicies.save(policy("t2"), null)
        assertEquals(a, p.escalationPolicies.get(a.id))
        assertEquals(listOf(a), p.escalationPolicies.byTeam("t1"))
        assertFailsWith<Conflict> { p.escalationPolicies.save(a, null) }
        p.escalationPolicies.save(a.copy(name = "Night", version = 2), 1)
        assertFailsWith<Conflict> { p.escalationPolicies.save(a.copy(name = "stale", version = 2), 1) }
        assertEquals("Night", p.escalationPolicies.get(a.id)!!.name)
        p.escalationPolicies.delete(a.id)
        assertNull(p.escalationPolicies.get(a.id))
    }

    @Test
    fun `runbooks round trip by team with their match and approval and enforce optimistic concurrency`() = suspendTest {
        fun rb(team: String) = mayday.runbooks.Runbook(Ids.new(), team, "Disk full", "1. Delete old logs.",
            mayday.runbooks.AlertMatch("prometheus", mapOf("service" to "db"), "disk-*"), listOf("resolve_alert"),
            createdBy = mayday.shared.Actor.system, createdAt = now, updatedAt = now)
        val a = rb("t1")
        p.runbooks.save(a, null)
        p.runbooks.save(rb("t2"), null)
        assertEquals(a, p.runbooks.get(a.id))
        assertEquals(listOf(a), p.runbooks.byTeam("t1"))
        assertFailsWith<Conflict> { p.runbooks.save(a, null) }
        val approved = a.copy(status = mayday.runbooks.RunbookStatus.APPROVED, approvedBy = mayday.shared.Actor(mayday.shared.ActorKind.USER, "u1", "Ana"),
            approvedAt = now, version = 2)
        p.runbooks.save(approved, 1)
        assertFailsWith<Conflict> { p.runbooks.save(a.copy(title = "stale", version = 2), 1) }
        assertEquals(approved, p.runbooks.get(a.id))
        p.runbooks.delete(a.id)
        assertNull(p.runbooks.get(a.id))
    }

    @Test
    fun `alerts and runs keep who resolved and the runbook`() = suspendTest {
        val agent = mayday.shared.Actor(mayday.shared.ActorKind.AGENT, "mayday", "Mayday agent")
        val alert = mayday.alerting.Alert(Ids.new(), "fp-${Ids.new()}", "prometheus", "Disk", severity = mayday.shared.Severity.SEV2,
            status = mayday.alerting.AlertStatus.RESOLVED, teamId = "t1", firstSeenAt = now, lastSeenAt = now, resolvedAt = now, resolvedBy = agent, resolvedUnderRunbook = "rb1")
        p.alerts.save(alert, null)
        assertEquals(alert, p.alerts.get(alert.id))
        val run = mayday.agents.AgentRun(Ids.new(), "mayday", "Mayday agent", mayday.agents.AgentAction.ANALYZE, mayday.agents.SubjectKind.ALERT, alert.id, "Disk",
            trigger = mayday.agents.RunTrigger.AUTO, requestedBy = mayday.shared.Actor.system, createdAt = now, alertId = alert.id, runbookId = "rb1",
            runbookTitle = "Disk full", raisedAt = now)
        p.runs.save(run, null)
        assertEquals(run, p.runs.get(run.id))
    }

    @Test
    fun `due escalations are claimed earliest first and wait for their lease`() = suspendTest {
        fun e(minutes: Long, status: mayday.escalation.EscalationStatus = mayday.escalation.EscalationStatus.ACTIVE, policy: String = "p1") =
            mayday.escalation.Escalation(Ids.new(), "t1", policy, status, 0, 0, now.plus(minutes, ChronoUnit.MINUTES),
                listOf(mayday.escalation.EscalationPage(now, 1, 0, listOf("u1"))), now, now)
        val late = e(-1); val early = e(-5); val future = e(10); val stopped = e(-9, mayday.escalation.EscalationStatus.ACKNOWLEDGED); val other = e(-2, policy = "p2")
        listOf(late, early, future, stopped, other).forEach { p.escalations.save(it, null) }
        assertEquals(early, p.escalations.get(early.id))
        assertEquals(setOf(late.id, early.id, future.id), p.escalations.activeByPolicy("p1").map { it.id }.toSet())
        val lease = java.time.Duration.ofSeconds(30)
        val first = p.escalations.claimDue(now, 1, lease)
        assertEquals(listOf(early.id), first.map { it.id })
        assertEquals(now.plus(lease), first.single().nextAt)
        assertEquals(first.single(), p.escalations.get(early.id), "the claim is saved")
        assertEquals(listOf(other.id, late.id), p.escalations.claimDue(now, 10, lease).map { it.id })
        assertEquals(emptyList(), p.escalations.claimDue(now, 10, lease), "a claimed escalation waits for its lease")
        assertEquals(setOf(early.id, other.id, late.id), p.escalations.claimDue(now.plus(lease), 10, lease).map { it.id }.toSet())
        val early2 = p.escalations.get(early.id)!!
        assertFailsWith<Conflict> { p.escalations.save(early, null) }
        val acked = early2.copy(status = mayday.escalation.EscalationStatus.ACKNOWLEDGED, nextAt = null, version = early2.version + 1)
        p.escalations.save(acked, early2.version)
        assertFailsWith<Conflict> { p.escalations.save(early2.copy(version = early2.version + 1), early2.version) }
        assertEquals(acked, p.escalations.get(early.id))
        assertEquals(setOf(other.id, late.id), p.escalations.claimDue(now.plus(lease).plus(lease), 10, lease).map { it.id }.toSet(), "a stopped escalation is not claimed")
    }

    @Test
    fun `concurrent claims never hand out a due escalation twice`() = suspendTest {
        val all = (1..40).map {
            mayday.escalation.Escalation(Ids.new(), "t1", "p1", mayday.escalation.EscalationStatus.ACTIVE, 0, 0, now.minusSeconds(it.toLong()), emptyList(), now, now)
        }
        all.forEach { p.escalations.save(it, null) }
        val claimed = (1..6).map { async(Dispatchers.IO) { p.escalations.claimDue(now, 10, java.time.Duration.ofSeconds(30)).map { it.id } } }.awaitAll().flatten()
        assertEquals(claimed.size, claimed.toSet().size, "no escalation is claimed twice")
        assertEquals(all.map { it.id }.toSet(), claimed.toSet())
    }
}
