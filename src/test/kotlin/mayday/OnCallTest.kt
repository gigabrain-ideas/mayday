package mayday

import mayday.identity.TeamRole
import mayday.oncall.OverrideInput
import mayday.oncall.Rotation
import mayday.oncall.Schedule
import mayday.oncall.ScheduleInput
import mayday.oncall.ShiftUnit
import mayday.shared.Conflict
import mayday.shared.Forbidden
import mayday.shared.Invalid
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RotationTest {
    private val t0 = Instant.parse("2026-01-05T09:00:00Z")
    private fun schedule(unit: ShiftUnit, length: Int = 1, zone: String = "UTC", start: Instant = t0, people: List<String> = listOf("a", "b", "c")) =
        Schedule("s", "t", "Primary", zone, start, length, unit, people, createdAt = t0, updatedAt = t0)

    @Test
    fun `a weekly rotation hands off every week in participant order and wraps`() {
        val s = schedule(ShiftUnit.WEEKS)
        val shifts = Rotation.shifts(s, t0, t0.plus(Duration.ofDays(28)))
        assertEquals(listOf("a", "b", "c", "a"), shifts.map { it.userId })
        assertEquals(t0.plus(Duration.ofDays(7)), shifts[0].endsAt)
    }

    @Test
    fun `nobody is on call before the rotation starts`() {
        val s = schedule(ShiftUnit.DAYS)
        val shifts = Rotation.shifts(s, t0.minus(Duration.ofHours(5)), t0.plus(Duration.ofHours(1)))
        assertNull(shifts.first().userId)
        assertEquals(t0, shifts.first().endsAt)
        assertEquals("a", shifts.last().userId)
    }

    @Test
    fun `a daily handoff keeps the local time across a daylight saving change`() {
        val zone = ZoneId.of("Europe/Berlin")
        val start = LocalDateTime.parse("2026-03-27T09:00").atZone(zone).toInstant()
        val s = schedule(ShiftUnit.DAYS, zone = "Europe/Berlin", start = start)
        val shifts = Rotation.shifts(s, start, start.plus(Duration.ofDays(4)))
        shifts.forEach { assertEquals(9, it.startsAt.atZone(zone).hour, "handoff at ${it.startsAt}") }
        assertEquals(Duration.ofHours(23), Duration.between(shifts[1].startsAt, shifts[2].startsAt))
    }

    @Test
    fun `a window inside one shift returns that shift cut to the window`() {
        val s = schedule(ShiftUnit.WEEKS, 2)
        val from = t0.plus(Duration.ofDays(15))
        val shifts = Rotation.shifts(s, from, from.plus(Duration.ofHours(1)))
        assertEquals(1, shifts.size)
        assertEquals("b", shifts[0].userId)
        assertEquals(from, shifts[0].startsAt)
    }

    @Test
    fun `a later override wins over an earlier one and both cut the rotation`() {
        val s = schedule(ShiftUnit.WEEKS)
        val base = Rotation.shifts(s, t0, t0.plus(Duration.ofDays(7)))
        val o1 = mayday.oncall.ScheduleOverride("o1", "s", "t", "x", t0.plus(Duration.ofDays(1)), t0.plus(Duration.ofDays(4)), createdBy = "u", createdAt = t0)
        val o2 = o1.copy(id = "o2", userId = "y", startsAt = t0.plus(Duration.ofDays(2)), endsAt = t0.plus(Duration.ofDays(3)), createdAt = t0.plusSeconds(1))
        val out = Rotation.apply(base, listOf(o2, o1))
        assertEquals(listOf("a", "x", "y", "x", "a"), out.map { it.userId })
        assertEquals(listOf(null, "o1", "o2", "o1", null), out.map { it.overrideId })
        assertEquals(t0, out.first().startsAt)
        assertEquals(t0.plus(Duration.ofDays(7)), out.last().endsAt)
    }

    @Test
    fun `an override longer than the window is cut to the window`() {
        val s = schedule(ShiftUnit.DAYS)
        val base = Rotation.shifts(s, t0, t0.plus(Duration.ofDays(1)))
        val o = mayday.oncall.ScheduleOverride("o", "s", "t", "x", t0.minus(Duration.ofDays(3)), t0.plus(Duration.ofDays(3)), createdBy = "u", createdAt = t0)
        val out = Rotation.apply(base, listOf(o))
        assertEquals(1, out.size)
        assertEquals(t0, out[0].startsAt)
        assertEquals(t0.plus(Duration.ofDays(1)), out[0].endsAt)
    }
}

class OnCallTest {
    private val f = Fixture()
    private val oncall = f.app.onCall
    private val now = Instant.now().truncatedTo(ChronoUnit.HOURS)

    private suspend fun schedule() = oncall.create(f.lead, ScheduleInput(f.team.id, "Primary", "UTC", now.minus(Duration.ofDays(1)), 1, ShiftUnit.WEEKS,
        listOf(f.teamAdminUser.id, f.memberUser.id)))

    @Test
    fun `only team owners create and change schedules`() = suspendTest {
        assertFailsWith<Forbidden> { oncall.create(f.member, ScheduleInput(f.team.id, "Mine", rotationStart = now)) }
        assertFailsWith<Forbidden> { oncall.create(f.lead, ScheduleInput(f.other.id, "Not mine", rotationStart = now)) }
        val s = schedule()
        assertEquals(f.teamAdminUser.id, oncall.onCall(f.viewer, listOf("checkout"), null).single().userId)
        assertFailsWith<Forbidden> { oncall.delete(f.member, s.id) }
        oncall.update(f.admin, s.id, ScheduleInput(name = "Primary 2", rotationStart = s.rotationStart, participants = s.participants), s.version)
        assertFailsWith<Conflict> { oncall.update(f.lead, s.id, ScheduleInput(name = "stale", rotationStart = now), s.version) }
    }

    @Test
    fun `schedules reject people outside the team, bad zones and bad lengths`() = suspendTest {
        assertFailsWith<Invalid> { oncall.create(f.lead, ScheduleInput(f.team.id, "X", rotationStart = now, participants = listOf(f.viewerUser.id))) }
        assertFailsWith<Invalid> { oncall.create(f.lead, ScheduleInput(f.team.id, "X", "Mars/Olympus", now)) }
        assertFailsWith<Invalid> { oncall.create(f.lead, ScheduleInput(f.team.id, "X", rotationStart = now, shiftLength = 0)) }
        assertFailsWith<Invalid> { oncall.create(f.lead, ScheduleInput(f.team.id, "X", rotationStart = now, participants = listOf(f.memberUser.id, f.memberUser.id))) }
    }

    @Test
    fun `a member covers for the owner with an override for a set period`() = suspendTest {
        val s = schedule()
        val o = oncall.createOverride(f.member, OverrideInput(s.id, f.memberUser.id, now, now.plus(Duration.ofDays(2)), "Lead is sick"))
        val current = oncall.onCall(f.viewer, listOf(f.team.id), now.plus(Duration.ofHours(1))).single()
        assertEquals(f.memberUser.id, current.userId)
        assertEquals(o.id, current.overrideId)
        assertEquals(now.plus(Duration.ofDays(2)), current.until)
        assertEquals(f.teamAdminUser.id, oncall.onCall(f.viewer, listOf(f.team.id), now.plus(Duration.ofDays(3))).single().userId)
    }

    @Test
    fun `members add only themselves, owners add any team member`() = suspendTest {
        val s = schedule()
        assertFailsWith<Forbidden> { oncall.createOverride(f.member, OverrideInput(s.id, f.teamAdminUser.id, now, now.plus(Duration.ofHours(4)))) }
        assertFailsWith<Forbidden> { oncall.createOverride(f.viewer, OverrideInput(s.id, f.viewerUser.id, now, now.plus(Duration.ofHours(4)))) }
        val o = oncall.createOverride(f.lead, OverrideInput(s.id, f.memberUser.id, now, now.plus(Duration.ofHours(4))))
        assertTrue(oncall.overrides(f.viewer, s.id, now, now.plus(Duration.ofDays(1))).any { it.id == o.id })
        assertFailsWith<Invalid> { oncall.createOverride(f.admin, OverrideInput(s.id, f.viewerUser.id, now, now.plus(Duration.ofHours(4)))) }
    }

    @Test
    fun `overrides must end after they start, end in the future and be at most a year long`() = suspendTest {
        val s = schedule()
        assertFailsWith<Invalid> { oncall.createOverride(f.lead, OverrideInput(s.id, f.memberUser.id, now, now)) }
        assertFailsWith<Invalid> { oncall.createOverride(f.lead, OverrideInput(s.id, f.memberUser.id, now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)))) }
        assertFailsWith<Invalid> { oncall.createOverride(f.lead, OverrideInput(s.id, f.memberUser.id, now, now.plus(Duration.ofDays(400)))) }
    }

    @Test
    fun `only the owner or the person who covers removes an override`() = suspendTest {
        val s = schedule()
        val byLead = oncall.createOverride(f.lead, OverrideInput(s.id, f.teamAdminUser.id, now, now.plus(Duration.ofHours(2))))
        assertFailsWith<Forbidden> { oncall.deleteOverride(f.member, byLead.id) }
        val mine = oncall.createOverride(f.member, OverrideInput(s.id, f.memberUser.id, now, now.plus(Duration.ofHours(2))))
        oncall.deleteOverride(f.member, mine.id)
        oncall.deleteOverride(f.lead, byLead.id)
        assertTrue(oncall.overrides(f.viewer, s.id, now, now.plus(Duration.ofDays(1))).isEmpty())
    }

    @Test
    fun `a person who leaves the team leaves the rotation and their future overrides end`() = suspendTest {
        val s = schedule()
        oncall.createOverride(f.member, OverrideInput(s.id, f.memberUser.id, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2))))
        f.app.identity.setTeamMember(f.lead, f.team.id, f.memberUser.id, null)
        assertEquals(listOf(f.teamAdminUser.id), oncall.schedule(f.viewer, s.id).participants)
        assertTrue(oncall.overrides(f.viewer, s.id, now, now.plus(Duration.ofDays(30))).isEmpty())
    }

    @Test
    fun `the shift window is limited`() = suspendTest {
        val s = schedule()
        assertFailsWith<Invalid> { oncall.shifts(f.viewer, s.id, now, now.plus(Duration.ofDays(94))) }
        assertFailsWith<Invalid> { oncall.shifts(f.viewer, s.id, now, now) }
        assertEquals(2, oncall.shifts(f.viewer, s.id, now, now.plus(Duration.ofDays(7))).size)
    }
}

class TeamMembersTest {
    private val f = Fixture()
    private val identity = f.app.identity

    @Test
    fun `team owners add, promote and remove members of their team only`() = suspendTest {
        identity.setTeamMember(f.lead, f.team.id, f.viewerUser.id, TeamRole.MEMBER)
        assertEquals(TeamRole.MEMBER, identity.teamMembers(f.viewer, "checkout").single { it.userId == f.viewerUser.id }.role)
        identity.setTeamMember(f.lead, f.team.id, f.memberUser.id, TeamRole.ADMIN)
        assertFailsWith<Forbidden> { identity.setTeamMember(f.lead, f.other.id, f.viewerUser.id, TeamRole.MEMBER) }
        assertFailsWith<Forbidden> { identity.setTeamMember(f.viewer, f.team.id, f.viewerUser.id, TeamRole.ADMIN) }
        identity.setTeamMember(f.lead, f.team.id, f.viewerUser.id, null)
        assertTrue(identity.teamMembers(f.viewer, f.team.id).none { it.userId == f.viewerUser.id })
    }
}
