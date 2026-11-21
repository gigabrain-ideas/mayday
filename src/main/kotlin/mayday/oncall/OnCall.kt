package mayday.oncall

import kotlinx.serialization.Serializable
import mayday.directory.DirectoryService
import mayday.directory.Team
import mayday.identity.Access
import mayday.identity.Principal
import mayday.identity.TeamMembershipListener
import mayday.identity.UserRepository
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.DomainEvent
import mayday.shared.EventPublisher
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Ts
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Serializable
enum class ShiftUnit { HOURS, DAYS, WEEKS }

/**
 * A rotation of team members. Shift k starts at [rotationStart] + k × [shiftLength] [shiftUnit], counted in [timeZone],
 * so a weekly handoff at 09:00 stays at 09:00 local time across daylight saving changes.
 */
@Serializable
data class Schedule(
    val id: String,
    val teamId: String,
    val name: String,
    val timeZone: String = "UTC",
    val rotationStart: Ts,
    val shiftLength: Int = 1,
    val shiftUnit: ShiftUnit = ShiftUnit.WEEKS,
    val participants: List<String> = emptyList(),
    val description: String = "",
    val createdAt: Ts,
    val updatedAt: Ts,
    val version: Long = 1,
)

/** [userId] covers the schedule from [startsAt] (inclusive) to [endsAt] (exclusive). A later override wins over an earlier one. */
@Serializable
data class ScheduleOverride(
    val id: String,
    val scheduleId: String,
    val teamId: String,
    val userId: String,
    val startsAt: Ts,
    val endsAt: Ts,
    val reason: String = "",
    val createdBy: String,
    val createdAt: Ts,
)

/** One continuous period with one person on call. [userId] is null when nobody is on call. */
@Serializable
data class Shift(
    val scheduleId: String,
    val userId: String? = null,
    val startsAt: Ts,
    val endsAt: Ts,
    val overrideId: String? = null,
)

@Serializable
data class OnCall(
    val scheduleId: String,
    val scheduleName: String,
    val teamId: String,
    val userId: String? = null,
    val until: Ts? = null,
    val overrideId: String? = null,
)

@Serializable
data class ScheduleInput(
    val teamId: String = "",
    val name: String,
    val timeZone: String = "UTC",
    val rotationStart: Ts,
    val shiftLength: Int = 1,
    val shiftUnit: ShiftUnit = ShiftUnit.WEEKS,
    val participants: List<String> = emptyList(),
    val description: String = "",
)

@Serializable
data class OverrideInput(
    val scheduleId: String,
    val userId: String,
    val startsAt: Ts,
    val endsAt: Ts,
    val reason: String = "",
)

interface ScheduleRepository {
    suspend fun get(id: String): Schedule?
    suspend fun byTeam(teamId: String): List<Schedule>
    suspend fun list(): List<Schedule>
    suspend fun save(schedule: Schedule, expectedVersion: Long?)
    suspend fun delete(id: String)
}

interface OverrideRepository {
    suspend fun get(id: String): ScheduleOverride?
    suspend fun save(override: ScheduleOverride)
    suspend fun delete(id: String)

    /** Overrides of the schedule that overlap [from, to), in creation order. */
    suspend fun overlapping(scheduleId: String, from: Instant, to: Instant): List<ScheduleOverride>

    /** Overrides of the user in the team that end after [after]. */
    suspend fun endingAfter(teamId: String, userId: String, after: Instant): List<ScheduleOverride>

    suspend fun deleteBySchedule(scheduleId: String)
}

/** Pure rotation math. No storage, no clock. */
object Rotation {
    fun zone(id: String): ZoneId = try { ZoneId.of(id) } catch (e: DateTimeException) { throw Invalid("Unknown time zone '$id'") }

    private fun boundary(s: Schedule, anchor: ZonedDateTime, k: Long): Instant = when (s.shiftUnit) {
        ShiftUnit.HOURS -> anchor.toInstant().plus(Duration.ofHours(k * s.shiftLength))
        ShiftUnit.DAYS -> anchor.plusDays(k * s.shiftLength).toInstant()
        ShiftUnit.WEEKS -> anchor.plusWeeks(k * s.shiftLength).toInstant()
    }

    /** Index of the shift that contains [t]. Negative before the rotation starts. */
    fun index(s: Schedule, t: Instant): Long {
        val anchor = s.rotationStart.atZone(zone(s.timeZone))
        val unitHours = when (s.shiftUnit) { ShiftUnit.HOURS -> 1L; ShiftUnit.DAYS -> 24L; ShiftUnit.WEEKS -> 168L }
        var k = Math.floorDiv(ChronoUnit.HOURS.between(s.rotationStart, t), unitHours * s.shiftLength)
        while (boundary(s, anchor, k + 1) <= t) k++
        while (boundary(s, anchor, k) > t) k--
        return k
    }

    /** Rotation shifts that overlap [from, to), cut to the window. Before [Schedule.rotationStart] nobody is on call. */
    fun shifts(s: Schedule, from: Instant, to: Instant): List<Shift> {
        if (!from.isBefore(to)) return emptyList()
        val out = ArrayList<Shift>()
        if (from < s.rotationStart) out += Shift(s.id, null, from, minOf(to, s.rotationStart))
        if (to <= s.rotationStart) return out
        val anchor = s.rotationStart.atZone(zone(s.timeZone))
        var k = index(s, maxOf(from, s.rotationStart))
        while (true) {
            val start = boundary(s, anchor, k)
            if (start >= to) break
            val end = boundary(s, anchor, k + 1)
            val user = s.participants.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(k, it.size).toInt()] }
            out += Shift(s.id, user, maxOf(start, from), minOf(end, to))
            k++
        }
        return out
    }

    /** Applies overrides in creation order: each one replaces what is below it in its period. Adjacent equal shifts are merged. */
    fun apply(base: List<Shift>, overrides: List<ScheduleOverride>): List<Shift> {
        var segs = base
        for (o in overrides.sortedBy { it.createdAt }) {
            val next = ArrayList<Shift>(segs.size + 2)
            var inserted = false
            for (s in segs) {
                if (s.endsAt <= o.startsAt || s.startsAt >= o.endsAt) { next += s; continue }
                if (s.startsAt < o.startsAt) next += s.copy(endsAt = o.startsAt)
                if (!inserted) { next += Shift(s.scheduleId, o.userId, maxOf(o.startsAt, s.startsAt), o.endsAt, o.id); inserted = true }
                if (s.endsAt > o.endsAt) next += s.copy(startsAt = o.endsAt)
            }
            val windowEnd = segs.lastOrNull()?.endsAt
            segs = next.map { if (it.overrideId == o.id && windowEnd != null && it.endsAt > windowEnd) it.copy(endsAt = windowEnd) else it }
        }
        return merge(segs)
    }

    private fun merge(segs: List<Shift>): List<Shift> {
        val out = ArrayList<Shift>(segs.size)
        for (s in segs) {
            val last = out.lastOrNull()
            if (last != null && last.userId == s.userId && last.overrideId == s.overrideId && last.endsAt == s.startsAt) out[out.size - 1] = last.copy(endsAt = s.endsAt)
            else out += s
        }
        return out
    }
}

private val MAX_WINDOW: Duration = Duration.ofDays(93)
private val MAX_OVERRIDE: Duration = Duration.ofDays(366)

class OnCallService(
    private val schedules: ScheduleRepository,
    private val overrides: OverrideRepository,
    private val users: UserRepository,
    private val directory: DirectoryService,
    private val events: EventPublisher,
    private val clock: Clock,
) : TeamMembershipListener {
    suspend fun schedules(by: Principal, teamId: String?): List<Schedule> {
        Access.requireRead(by)
        val list = if (teamId.isNullOrBlank()) schedules.list() else schedules.byTeam(directory.team(teamId).id)
        return list.sortedWith(compareBy({ it.teamId }, { it.name.lowercase() }))
    }

    suspend fun schedule(by: Principal, id: String): Schedule {
        Access.requireRead(by)
        return schedules.get(id) ?: throw NotFound("Schedule")
    }

    suspend fun create(by: Principal, input: ScheduleInput): Schedule {
        val team = directory.team(input.teamId)
        requireOwner(by, team)
        val now = clock.now()
        val s = validated(Schedule(Ids.new(), team.id, input.name.trim(), input.timeZone, input.rotationStart, input.shiftLength, input.shiftUnit,
            input.participants, input.description, now, now))
        schedules.save(s, null)
        changed(s.teamId, s.id)
        return s
    }

    suspend fun update(by: Principal, id: String, input: ScheduleInput, expectedVersion: Long?): Schedule {
        val cur = schedules.get(id) ?: throw NotFound("Schedule")
        requireOwner(by, directory.team(cur.teamId))
        if (expectedVersion != null && expectedVersion != cur.version) throw Conflict("The schedule changed. Reload and try again.")
        val s = validated(cur.copy(name = input.name.trim(), timeZone = input.timeZone, rotationStart = input.rotationStart, shiftLength = input.shiftLength,
            shiftUnit = input.shiftUnit, participants = input.participants, description = input.description, updatedAt = clock.now(), version = cur.version + 1))
        schedules.save(s, cur.version)
        changed(s.teamId, s.id)
        return s
    }

    suspend fun delete(by: Principal, id: String) {
        val s = schedules.get(id) ?: throw NotFound("Schedule")
        requireOwner(by, directory.team(s.teamId))
        overrides.deleteBySchedule(s.id)
        schedules.delete(s.id)
        changed(s.teamId, s.id)
    }

    /** Team owners plug in any team member. Team members plug in only themselves. */
    suspend fun createOverride(by: Principal, input: OverrideInput): ScheduleOverride {
        Access.requireWrite(by)
        val s = schedules.get(input.scheduleId) ?: throw NotFound("Schedule")
        val team = directory.team(s.teamId)
        val owner = Access.isTeamAdmin(by, team.id, team.projectId)
        if (!owner && (by.user?.id != input.userId || by.user.teams[team.id] == null)) {
            throw Forbidden("Team members can add only themselves as an override. Team owners can add any team member.")
        }
        val target = users.get(input.userId) ?: throw NotFound("User")
        if (target.teams[team.id] == null) throw Invalid("${target.name} is not a member of ${team.name}")
        if (target.disabled) throw Invalid("${target.name} is disabled")
        if (!input.startsAt.isBefore(input.endsAt)) throw Invalid("The override must end after it starts")
        if (!input.endsAt.isAfter(clock.now())) throw Invalid("The override must end in the future")
        if (Duration.between(input.startsAt, input.endsAt) > MAX_OVERRIDE) throw Invalid("An override can be at most 366 days long")
        val o = ScheduleOverride(Ids.new(), s.id, team.id, target.id, input.startsAt, input.endsAt, input.reason.trim().take(500),
            by.user?.id ?: by.actor.id, clock.now())
        overrides.save(o)
        changed(team.id, s.id)
        return o
    }

    suspend fun deleteOverride(by: Principal, id: String) {
        Access.requireWrite(by)
        val o = overrides.get(id) ?: throw NotFound("Override")
        val team = directory.team(o.teamId)
        if (!Access.isTeamAdmin(by, team.id, team.projectId) && by.user?.id != o.userId) {
            throw Forbidden("Only team owners and the person who covers can remove an override")
        }
        overrides.delete(o.id)
        changed(team.id, o.scheduleId)
    }

    suspend fun overrides(by: Principal, scheduleId: String, from: Instant, to: Instant): List<ScheduleOverride> {
        Access.requireRead(by)
        window(from, to)
        return overrides.overlapping(scheduleId, from, to).sortedBy { it.startsAt }
    }

    suspend fun shifts(by: Principal, scheduleId: String, from: Instant, to: Instant): List<Shift> {
        Access.requireRead(by)
        window(from, to)
        val s = schedules.get(scheduleId) ?: throw NotFound("Schedule")
        return compute(s, from, to)
    }

    /** Who is on call at [at] for each schedule of the teams. No teams means all teams. */
    suspend fun onCall(by: Principal, teamIds: List<String>, at: Instant?): List<OnCall> {
        Access.requireRead(by)
        val t = at ?: clock.now()
        val list = if (teamIds.isEmpty()) schedules.list() else teamIds.flatMap { schedules.byTeam(directory.team(it).id) }
        return list.sortedWith(compareBy({ it.teamId }, { it.name.lowercase() })).map { s ->
            val first = compute(s, t, t.plus(horizon(s))).firstOrNull()
            OnCall(s.id, s.name, s.teamId, first?.userId, first?.endsAt, first?.overrideId)
        }
    }

    /** A person who leaves a team leaves its rotations, and their future overrides of that team end. */
    override suspend fun membershipRemoved(teamId: String, userId: String) {
        val now = clock.now()
        for (s in schedules.byTeam(teamId)) {
            if (userId !in s.participants) continue
            val updated = s.copy(participants = s.participants - userId, updatedAt = now, version = s.version + 1)
            runCatching { schedules.save(updated, s.version) }
        }
        for (o in overrides.endingAfter(teamId, userId, now)) {
            if (o.startsAt >= now) overrides.delete(o.id) else overrides.save(o.copy(endsAt = now))
        }
        changed(teamId, "")
    }

    private suspend fun compute(s: Schedule, from: Instant, to: Instant): List<Shift> =
        Rotation.apply(Rotation.shifts(s, from, to), overrides.overlapping(s.id, from, to))

    private fun horizon(s: Schedule): Duration = when (s.shiftUnit) {
        ShiftUnit.HOURS -> Duration.ofHours(s.shiftLength.toLong())
        ShiftUnit.DAYS -> Duration.ofDays(s.shiftLength.toLong())
        ShiftUnit.WEEKS -> Duration.ofDays(7L * s.shiftLength)
    }.multipliedBy(2).coerceAtMost(MAX_WINDOW)

    private fun window(from: Instant, to: Instant) {
        if (!from.isBefore(to)) throw Invalid("'to' must be after 'from'")
        if (Duration.between(from, to) > MAX_WINDOW) throw Invalid("The time window can be at most 93 days")
    }

    private fun requireOwner(by: Principal, team: Team) {
        Access.requireWrite(by)
        if (!Access.isTeamAdmin(by, team.id, team.projectId)) throw Forbidden("Only team owners change schedules")
    }

    private suspend fun validated(s: Schedule): Schedule {
        if (s.name.isBlank()) throw Invalid("Name is required")
        Rotation.zone(s.timeZone)
        val max = when (s.shiftUnit) { ShiftUnit.HOURS -> 24 * 31; ShiftUnit.DAYS -> 365; ShiftUnit.WEEKS -> 52 }
        if (s.shiftLength !in 1..max) throw Invalid("Shift length must be from 1 to $max ${s.shiftUnit.name.lowercase()}")
        if (s.participants.size != s.participants.toSet().size) throw Invalid("A person can be in a rotation only once")
        for (id in s.participants) {
            val u = users.get(id) ?: throw Invalid("Unknown user $id")
            if (u.teams[s.teamId] == null) throw Invalid("${u.name} is not a member of the team")
        }
        return s
    }

    private suspend fun changed(teamId: String, scheduleId: String) =
        events.publish(DomainEvent("oncall.changed", scheduleId, clock.now(), listOf(teamId)))
}
