package mayday

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import mayday.alerting.AlertSignal
import mayday.escalation.EscalationLevel
import mayday.escalation.EscalationPolicyInput
import mayday.escalation.EscalationStatus
import mayday.escalation.EscalationTarget
import mayday.escalation.TargetKind
import mayday.alerting.AlertStatus
import mayday.incidents.IncidentPatch
import mayday.incidents.IncidentStatus
import mayday.incidents.NewIncident
import mayday.oncall.OverrideInput
import mayday.oncall.ScheduleInput
import mayday.oncall.ShiftUnit
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.shared.Conflict
import mayday.shared.Forbidden
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Severity
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EscalationTest {
    private val clock = TestClock()
    private val f = Fixture(clock = clock)
    private val esc = f.app.escalation
    private val now get() = clock.now()

    private suspend fun primary() = f.app.onCall.create(f.lead, ScheduleInput(f.team.id, "Primary", "UTC", now.minus(Duration.ofDays(1)), 1, ShiftUnit.WEEKS,
        listOf(f.teamAdminUser.id, f.memberUser.id)))

    private fun schedule(id: String) = EscalationTarget(TargetKind.SCHEDULE, id)
    private fun user(id: String) = EscalationTarget(TargetKind.USER, id)

    private suspend fun twoLevels(repeat: Int = 0) = esc.create(f.lead, EscalationPolicyInput(f.team.id, "Default",
        levels = listOf(EscalationLevel(5, listOf(schedule(primary().id))), EscalationLevel(10, listOf(user(f.memberUser.id)))), repeat = repeat, teamDefault = true))

    private suspend fun fire(severity: Severity = Severity.SEV2, title: String = "Checkout errors") =
        f.app.alerts.ingest(f.admin, AlertSignal(title, severity, "checkout")).alert

    @Test
    fun `only team owners create and change escalation policies`() = suspendTest {
        val levels = listOf(EscalationLevel(5, listOf(user(f.memberUser.id))))
        assertFailsWith<Forbidden> { esc.create(f.member, EscalationPolicyInput(f.team.id, "Mine", levels = levels)) }
        assertFailsWith<Forbidden> { esc.create(f.lead, EscalationPolicyInput(f.other.id, "Not mine", levels = levels)) }
        val p = esc.create(f.lead, EscalationPolicyInput(f.team.id, "Default", levels = levels))
        assertEquals(listOf(p.id), esc.policies(f.viewer, "checkout").map { it.id })
        assertFailsWith<Forbidden> { esc.delete(f.member, p.id) }
        val renamed = esc.update(f.admin, p.id, EscalationPolicyInput(name = "Renamed", levels = levels), p.version)
        assertEquals(2, renamed.version)
        assertFailsWith<Conflict> { esc.update(f.lead, p.id, EscalationPolicyInput(name = "stale", levels = levels), p.version) }
        esc.delete(f.lead, p.id)
        assertFailsWith<NotFound> { esc.policy(f.viewer, p.id) }
    }

    @Test
    fun `policies reject empty levels, bad delays, bad repeats and unknown targets`() = suspendTest {
        val ok = listOf(user(f.memberUser.id))
        suspend fun bad(levels: List<EscalationLevel>, repeat: Int = 0) =
            assertFailsWith<Invalid> { esc.create(f.lead, EscalationPolicyInput(f.team.id, "X", levels = levels, repeat = repeat)) }
        bad(emptyList())
        bad(listOf(EscalationLevel(5, emptyList())))
        bad(listOf(EscalationLevel(0, ok)))
        bad(listOf(EscalationLevel(31, ok)))
        bad(List(11) { EscalationLevel(5, ok) })
        bad(listOf(EscalationLevel(5, ok)), repeat = 10)
        bad(listOf(EscalationLevel(5, listOf(schedule("missing")))))
        bad(listOf(EscalationLevel(5, listOf(user("missing")))))
        bad(listOf(EscalationLevel(5, ok + ok)))
        assertFailsWith<Invalid> { esc.create(f.lead, EscalationPolicyInput(f.team.id, " ", levels = listOf(EscalationLevel(5, ok)))) }
    }

    @Test
    fun `an administrator limits the delay that teams can set`() = suspendTest {
        f.app.policy.set(f.admin, Scope.ORG, "org", Keys.ESCALATION_MAX_ACK_MINUTES, "15", Enforcement.MINIMUM)
        val levels = { m: Int -> listOf(EscalationLevel(m, listOf(user(f.memberUser.id)))) }
        assertFailsWith<Invalid> { esc.create(f.lead, EscalationPolicyInput(f.team.id, "Slow", levels = levels(20))) }
        esc.create(f.lead, EscalationPolicyInput(f.team.id, "Fast", levels = levels(10)))
        assertFailsWith<mayday.shared.DomainException> { f.app.policy.set(f.lead, Scope.TEAM, f.team.id, Keys.ESCALATION_MAX_ACK_MINUTES, "60", Enforcement.NONE) }
    }

    @Test
    fun `a team has at most one default policy`() = suspendTest {
        val levels = listOf(EscalationLevel(5, listOf(user(f.memberUser.id))))
        val a = esc.create(f.lead, EscalationPolicyInput(f.team.id, "A", levels = levels, teamDefault = true))
        val b = esc.create(f.lead, EscalationPolicyInput(f.team.id, "B", levels = levels, teamDefault = true))
        assertFalse(esc.policy(f.viewer, a.id).teamDefault)
        assertTrue(esc.policy(f.viewer, b.id).teamDefault)
        assertEquals(b.id, esc.policies(f.viewer, f.team.id).first().id)
    }

    private val automaticAgent = mayday.identity.Principal(mayday.shared.Actor(mayday.shared.ActorKind.AGENT, "mayday", "Mayday agent"), null,
        mayday.identity.OrgRole.MEMBER, setOf(mayday.identity.TokenScope.READ, mayday.identity.TokenScope.WRITE), "run-1")

    @Test
    fun `an automatic agent acknowledges an alert and the on-call person is still paged`() = suspendTest {
        twoLevels()
        val alert = fire()
        val acked = f.app.alerts.acknowledge(automaticAgent, alert.id)
        assertEquals(AlertStatus.ACKNOWLEDGED, acked.status)
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, alert.id).status, "a human must still acknowledge")
        clock.advance(Duration.ofMinutes(5))
        esc.tick()
        assertEquals(listOf(f.memberUser.id), f.pager.pages.last().recipients.map { it.id }, "the next level is paged")
        val human = f.app.alerts.acknowledge(f.member, alert.id)
        assertEquals(f.memberUser.id, human.acknowledgedBy?.id)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `an agent that acts for a person acknowledges for that person`() = suspendTest {
        twoLevels()
        val alert = fire()
        val forPerson = mayday.identity.Principal(mayday.shared.Actor(mayday.shared.ActorKind.AGENT, "mayday", "Mayday agent", onBehalfOf = f.memberUser.id),
            f.memberUser, mayday.identity.OrgRole.MEMBER, mayday.identity.Principal.DELEGATED, "run-2")
        f.app.alerts.acknowledge(forPerson, alert.id)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `an automatic agent moves the incident on and the raised alerts keep paging until a human acknowledges`() = suspendTest {
        twoLevels()
        val inc = f.app.incidents.declare(f.member, NewIncident("Checkout returns errors", severity = Severity.SEV2, teamIds = listOf(f.team.id)))
        val alertId = inc.alertIds.single()
        val byAgent = f.app.incidents.update(automaticAgent, inc.key, IncidentPatch(status = IncidentStatus.IDENTIFIED))
        assertNull(byAgent.acknowledgedAt, "only a human acknowledges an incident")
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, alertId).status)
        val byHuman = f.app.incidents.update(f.member, inc.key, IncidentPatch(status = IncidentStatus.MONITORING))
        assertTrue(byHuman.acknowledgedAt != null)
        assertEquals(f.memberUser.id, f.app.alerts.get(f.viewer, alertId).acknowledgedBy?.id)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, alertId).status)
    }

    @Test
    fun `a human confirms the status that an automatic agent set and paging stops`() = suspendTest {
        twoLevels()
        val inc = f.app.incidents.declare(f.member, NewIncident("Checkout returns errors", severity = Severity.SEV2, teamIds = listOf(f.team.id)))
        f.app.incidents.update(automaticAgent, inc.key, IncidentPatch(status = IncidentStatus.IDENTIFIED))
        val edited = f.app.incidents.update(f.member, inc.key, IncidentPatch(title = "Checkout returns 500"))
        assertNull(edited.acknowledgedAt, "an edit without a status is no acknowledgement")
        val confirmed = f.app.incidents.update(f.member, inc.key, IncidentPatch(status = IncidentStatus.IDENTIFIED))
        assertTrue(confirmed.acknowledgedAt != null)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, inc.alertIds.single()).status)
    }

    @Test
    fun `a team added after only an automatic agent acknowledged is paged`() = suspendTest {
        twoLevels()
        val inc = f.app.incidents.declare(f.member, NewIncident("Payments slow", severity = Severity.SEV2, teamIds = listOf(f.other.id)))
        f.app.alerts.acknowledge(automaticAgent, inc.alertIds.single())
        f.app.incidents.update(automaticAgent, inc.key, IncidentPatch(status = IncidentStatus.IDENTIFIED))
        val next = f.app.incidents.update(f.member, inc.key, IncidentPatch(teamIds = listOf(f.other.id, f.team.id)))
        assertEquals(2, next.alertIds.size)
        assertEquals(listOf(f.teamAdminUser.id), f.pager.pages.single().recipients.map { it.id })
    }

    @Test
    fun `raised alerts stop paging when the incident is acknowledged and resolve with it`() = suspendTest {
        twoLevels()
        val inc = f.app.incidents.declare(f.member, NewIncident("Checkout returns errors", severity = Severity.SEV2, teamIds = listOf(f.team.id)))
        val alertId = inc.alertIds.single()
        f.app.incidents.update(f.member, inc.key, IncidentPatch(status = IncidentStatus.IDENTIFIED))
        assertEquals(AlertStatus.ACKNOWLEDGED, f.app.alerts.get(f.viewer, alertId).status)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, alertId).status)
        clock.advance(Duration.ofHours(1))
        esc.tick()
        assertEquals(1, f.pager.pages.size)
        f.app.incidents.update(f.member, inc.key, IncidentPatch(status = IncidentStatus.RESOLVED))
        assertEquals(AlertStatus.RESOLVED, f.app.alerts.get(f.viewer, alertId).status)
    }

    @Test
    fun `a team added before anybody acknowledges is paged`() = suspendTest {
        twoLevels()
        val inc = f.app.incidents.declare(f.member, NewIncident("Payments slow", severity = Severity.SEV2, teamIds = listOf(f.other.id)))
        assertTrue(f.pager.pages.isEmpty(), "SRE has no escalation policy")
        val next = f.app.incidents.update(f.member, inc.key, IncidentPatch(teamIds = listOf(f.other.id, f.team.id)))
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, next.alertIds.last()).status)
        assertEquals(listOf(f.teamAdminUser.id), f.pager.pages.single().recipients.map { it.id })
    }

    @Test
    fun `a declared incident pages the person on call through its raised alert`() = suspendTest {
        twoLevels()
        val inc = f.app.incidents.declare(f.member, NewIncident("Checkout returns errors", severity = Severity.SEV2, teamIds = listOf(f.team.id)))
        val e = esc.escalation(f.viewer, inc.alertIds.single())
        assertEquals(EscalationStatus.ACTIVE, e.status)
        assertEquals(listOf(f.teamAdminUser.id), f.pager.pages.single().recipients.map { it.id })
    }

    @Test
    fun `a new alert pages the person on call and escalates when nobody acknowledges`() = suspendTest {
        val p = twoLevels()
        val alert = fire()
        val e = esc.escalation(f.viewer, alert.id)
        assertEquals(EscalationStatus.ACTIVE, e.status)
        assertEquals(p.id, e.policyId)
        assertEquals(listOf(f.teamAdminUser.id), e.pages.single().userIds)
        assertEquals(now.plus(Duration.ofMinutes(5)), e.nextAt)
        assertEquals(listOf(f.teamAdminUser.id), f.pager.pages.single().recipients.map { it.id })
        assertEquals("http://mayday.test/alerts/${alert.id}", f.pager.pages.single().alertUrl)

        clock.advance(Duration.ofMinutes(4))
        esc.tick()
        assertEquals(1, f.pager.pages.size)
        clock.advance(Duration.ofMinutes(1))
        esc.tick()
        assertEquals(listOf(f.memberUser.id), f.pager.pages.last().recipients.map { it.id })
        assertEquals(2, f.pager.pages.last().level)

        f.app.alerts.acknowledge(f.member, alert.id)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, alert.id).status)
        clock.advance(Duration.ofHours(1))
        esc.tick()
        assertEquals(2, f.pager.pages.size)
    }

    @Test
    fun `an override on the schedule gets the page`() = suspendTest {
        twoLevels()
        val s = f.app.onCall.schedules(f.viewer, f.team.id).single()
        f.app.onCall.createOverride(f.member, OverrideInput(s.id, f.memberUser.id, now, now.plus(Duration.ofHours(4)), "Lead is sick"))
        fire()
        assertEquals(listOf(f.memberUser.id), f.pager.pages.single().recipients.map { it.id })
    }

    @Test
    fun `after the last level the policy repeats and then is exhausted`() = suspendTest {
        twoLevels(repeat = 1)
        val alert = fire()
        repeat(3) { clock.advance(Duration.ofMinutes(10)); esc.tick() }
        val e = esc.escalation(f.viewer, alert.id)
        assertEquals(EscalationStatus.ACTIVE, e.status)
        assertEquals(listOf(1 to 0, 2 to 0, 1 to 1, 2 to 1), e.pages.map { it.level to it.cycle })
        clock.advance(Duration.ofMinutes(10))
        esc.tick()
        assertEquals(EscalationStatus.EXHAUSTED, esc.escalation(f.viewer, alert.id).status)
        assertNull(esc.escalation(f.viewer, alert.id).nextAt)
        assertEquals(4, f.pager.pages.size)
    }

    @Test
    fun `alerts below the paging severity page nobody`() = suspendTest {
        twoLevels()
        val alert = fire(Severity.SEV5)
        assertFailsWith<NotFound> { esc.escalation(f.viewer, alert.id) }
        f.app.policy.set(f.lead, Scope.TEAM, f.team.id, Keys.ESCALATION_MIN_SEVERITY, "NONE", Enforcement.NONE)
        assertFailsWith<NotFound> { esc.escalation(f.viewer, fire(Severity.SEV1, "Down").id) }
        assertTrue(f.pager.pages.isEmpty())
    }

    @Test
    fun `a level that reaches nobody moves to the next level on the next tick`() = suspendTest {
        val empty = f.app.onCall.create(f.lead, ScheduleInput(f.team.id, "Empty", rotationStart = now))
        esc.create(f.lead, EscalationPolicyInput(f.team.id, "Default", teamDefault = true,
            levels = listOf(EscalationLevel(30, listOf(schedule(empty.id))), EscalationLevel(30, listOf(user(f.teamAdminUser.id))))))
        val alert = fire()
        assertTrue(f.pager.pages.isEmpty())
        assertEquals(now, esc.escalation(f.viewer, alert.id).nextAt)
        esc.tick()
        assertEquals(listOf(f.teamAdminUser.id), f.pager.pages.single().recipients.map { it.id })
    }

    @Test
    fun `responders escalate now, viewers cannot, and only firing alerts escalate`() = suspendTest {
        twoLevels()
        val alert = fire()
        assertFailsWith<Forbidden> { esc.escalateNow(f.viewer, alert.id) }
        val e = esc.escalateNow(f.member, alert.id)
        assertEquals(2, e.pages.last().level)
        assertTrue(e.pages.last().manual)
        assertTrue(f.pager.pages.last().manual)
        f.app.alerts.resolve(f.member, alert.id)
        assertEquals(EscalationStatus.RESOLVED, esc.escalation(f.viewer, alert.id).status)
        assertFailsWith<Invalid> { esc.escalateNow(f.member, alert.id) }
    }

    @Test
    fun `escalate now starts the default policy for an alert below the paging severity`() = suspendTest {
        twoLevels()
        val alert = fire(Severity.SEV5)
        val e = esc.escalateNow(f.member, alert.id)
        assertEquals(listOf(f.teamAdminUser.id), e.pages.single().userIds)
    }

    @Test
    fun `a reopened alert starts again at level 1 and keeps the history`() = suspendTest {
        twoLevels()
        val alert = fire()
        clock.advance(Duration.ofMinutes(5))
        esc.tick()
        f.app.alerts.resolve(f.member, alert.id)
        clock.advance(Duration.ofMinutes(1))
        fire()
        val e = esc.escalation(f.viewer, alert.id)
        assertEquals(EscalationStatus.ACTIVE, e.status)
        assertEquals(0, e.level)
        assertEquals(listOf(1, 2, 1), e.pages.map { it.level })
    }

    @Test
    fun `deleting a policy cancels its escalations`() = suspendTest {
        val p = twoLevels()
        val alert = fire()
        esc.delete(f.lead, p.id)
        assertEquals(EscalationStatus.CANCELLED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `parallel ticks send each page once`() = suspendTest {
        twoLevels()
        val alert = fire()
        clock.advance(Duration.ofMinutes(5))
        List(8) { async(Dispatchers.Default) { esc.tick() } }.awaitAll()
        assertEquals(listOf(1, 2), f.pager.pages.map { it.level })
        assertEquals(2, esc.escalation(f.viewer, alert.id).pages.size)
    }
}
