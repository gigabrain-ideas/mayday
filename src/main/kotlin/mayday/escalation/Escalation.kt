package mayday.escalation

import kotlinx.serialization.Serializable
import mayday.alerting.Alert
import mayday.alerting.AlertLifecycle
import mayday.alerting.AlertRepository
import mayday.alerting.AlertStatus
import mayday.alerting.waitsForHuman
import mayday.directory.DirectoryService
import mayday.directory.Team
import mayday.identity.Access
import mayday.identity.Principal
import mayday.identity.UserRepository
import mayday.oncall.OnCallService
import mayday.policy.Keys
import mayday.policy.PolicyReader
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.DomainEvent
import mayday.shared.EventPublisher
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Severity
import mayday.shared.Ts
import java.time.Duration
import java.time.Instant

@Serializable
enum class TargetKind { SCHEDULE, USER }

@Serializable
data class EscalationTarget(val kind: TargetKind, val id: String)

/** The [targets] are paged together. The next level is paged after [delayMinutes] without acknowledgement. */
@Serializable
data class EscalationLevel(val delayMinutes: Int, val targets: List<EscalationTarget>)

@Serializable
data class EscalationPolicy(
    val id: String,
    val teamId: String,
    val name: String,
    val description: String = "",
    val levels: List<EscalationLevel>,
    val repeat: Int = 0,
    val teamDefault: Boolean = false,
    val createdAt: Ts,
    val updatedAt: Ts,
    val version: Long = 1,
)

@Serializable
data class EscalationPolicyInput(
    val teamId: String = "",
    val name: String,
    val description: String = "",
    val levels: List<EscalationLevel> = emptyList(),
    val repeat: Int = 0,
    val teamDefault: Boolean = false,
)

@Serializable
enum class EscalationStatus { ACTIVE, ACKNOWLEDGED, RESOLVED, EXHAUSTED, CANCELLED }

/** [level] is 1-based. [userIds] is empty when the level reached nobody. */
@Serializable
data class EscalationPage(val at: Ts, val level: Int, val cycle: Int, val userIds: List<String> = emptyList(), val manual: Boolean = false)

/** The escalation of one alert. [id] is the alert id. [level] is the 0-based index of the level paged last. */
@Serializable
data class Escalation(
    val id: String,
    val teamId: String,
    val policyId: String,
    val status: EscalationStatus,
    val level: Int = 0,
    val cycle: Int = 0,
    val nextAt: Ts? = null,
    val pages: List<EscalationPage> = emptyList(),
    val startedAt: Ts,
    val updatedAt: Ts,
    val version: Long = 1,
)

interface EscalationPolicyRepository {
    suspend fun get(id: String): EscalationPolicy?
    suspend fun byTeam(teamId: String): List<EscalationPolicy>
    suspend fun save(policy: EscalationPolicy, expectedVersion: Long?)
    suspend fun delete(id: String)
}

interface EscalationRepository {
    suspend fun get(id: String): Escalation?
    suspend fun save(escalation: Escalation, expectedVersion: Long?)

    /** ACTIVE escalations with [Escalation.nextAt] at or before [at], earliest first. */
    suspend fun due(at: Instant, limit: Int): List<Escalation>

    suspend fun activeByPolicy(policyId: String): List<Escalation>
}

@Serializable
data class PageNotice(
    val alertId: String,
    val alertTitle: String,
    val alertUrl: String,
    val severity: Severity,
    val teamId: String,
    val policyId: String,
    val policyName: String,
    val level: Int,
    val cycle: Int,
    val manual: Boolean,
    val recipients: List<PageRecipient>,
)

@Serializable
data class PageRecipient(val id: String, val name: String, val email: String)

/** Port that delivers a page to people (webhook, SMS, push, chat). */
fun interface Pager {
    suspend fun page(notice: PageNotice)

    companion object {
        val none = Pager { }
    }
}

private const val MAX_LEVELS = 10
private const val MAX_TARGETS = 10
private const val MAX_REPEAT = 9
private const val TICK_BATCH = 500

class EscalationService(
    private val policies: EscalationPolicyRepository,
    private val escalations: EscalationRepository,
    private val alerts: AlertRepository,
    private val onCall: OnCallService,
    private val users: UserRepository,
    private val directory: DirectoryService,
    private val settings: PolicyReader,
    private val events: EventPublisher,
    private val clock: Clock,
    private val publicUrl: String,
    @Volatile var pager: Pager = Pager.none,
) : AlertLifecycle {

    suspend fun policies(by: Principal, teamId: String): List<EscalationPolicy> {
        Access.requireRead(by)
        return policies.byTeam(directory.team(teamId).id).sortedWith(compareBy({ !it.teamDefault }, { it.name.lowercase() }))
    }

    suspend fun policy(by: Principal, id: String): EscalationPolicy {
        Access.requireRead(by)
        return policies.get(id) ?: throw NotFound("Escalation policy")
    }

    suspend fun create(by: Principal, input: EscalationPolicyInput): EscalationPolicy {
        val team = directory.team(input.teamId)
        requireOwner(by, team)
        val now = clock.now()
        val p = validated(EscalationPolicy(Ids.new(), team.id, input.name.trim(), input.description.trim(), input.levels, input.repeat, input.teamDefault, now, now))
        policies.save(p, null)
        if (p.teamDefault) clearOtherDefaults(p)
        changed(p.teamId, p.id)
        return p
    }

    suspend fun update(by: Principal, id: String, input: EscalationPolicyInput, expectedVersion: Long?): EscalationPolicy {
        val cur = policies.get(id) ?: throw NotFound("Escalation policy")
        requireOwner(by, directory.team(cur.teamId))
        if (expectedVersion != null && expectedVersion != cur.version) throw Conflict("The escalation policy changed. Reload and try again.")
        val p = validated(cur.copy(name = input.name.trim(), description = input.description.trim(), levels = input.levels, repeat = input.repeat,
            teamDefault = input.teamDefault, updatedAt = clock.now(), version = cur.version + 1))
        policies.save(p, cur.version)
        if (p.teamDefault) clearOtherDefaults(p)
        changed(p.teamId, p.id)
        return p
    }

    suspend fun delete(by: Principal, id: String) {
        val p = policies.get(id) ?: throw NotFound("Escalation policy")
        requireOwner(by, directory.team(p.teamId))
        policies.delete(p.id)
        for (e in escalations.activeByPolicy(p.id)) stop(e.id, EscalationStatus.CANCELLED)
        changed(p.teamId, p.id)
    }

    suspend fun escalation(by: Principal, alertId: String): Escalation {
        Access.requireRead(by)
        return escalations.get(alertId) ?: throw NotFound("Escalation")
    }

    /** Pages the next level now. An alert without an escalation starts the team default policy at level 1. */
    suspend fun escalateNow(by: Principal, alertId: String): Escalation {
        Access.requireWrite(by)
        val alert = alerts.get(alertId) ?: throw NotFound("Alert")
        if (!alert.waitsForHuman) throw Invalid("Only an alert that waits for a person can be escalated")
        val cur = escalations.get(alert.id)
        if (cur == null || cur.status != EscalationStatus.ACTIVE) {
            val policy = defaultPolicy(alert.teamId) ?: throw Invalid("The team has no default escalation policy")
            return begin(alert, policy, cur, manual = true) ?: throw Conflict("The escalation changed. Reload and try again.")
        }
        val policy = policies.get(cur.policyId) ?: throw Invalid("The escalation policy was deleted")
        return advance(cur, alert, policy, manual = true) ?: throw Conflict("The escalation changed. Reload and try again.")
    }

    override suspend fun opened(alert: Alert) {
        val min = settings.severity(Keys.ESCALATION_MIN_SEVERITY, listOf(alert.teamId)) ?: return
        if (!alert.severity.atLeast(min)) return
        val policy = defaultPolicy(alert.teamId) ?: return
        val cur = escalations.get(alert.id)
        if (cur?.status == EscalationStatus.ACTIVE) return
        begin(alert, policy, cur, manual = false)
    }

    override suspend fun closed(alert: Alert) {
        val status = if (alert.status == AlertStatus.RESOLVED) EscalationStatus.RESOLVED else EscalationStatus.ACKNOWLEDGED
        stop(alert.id, status)
    }

    /** Pages the next level of every due escalation. Returns the number of escalations handled. */
    suspend fun tick(): Int {
        val due = escalations.due(clock.now(), TICK_BATCH)
        for (e in due) {
            runCatching {
                val alert = alerts.get(e.id)
                val policy = policies.get(e.policyId)
                when {
                    alert == null || policy == null -> stop(e.id, EscalationStatus.CANCELLED)
                    alert.status == AlertStatus.RESOLVED -> stop(e.id, EscalationStatus.RESOLVED)
                    alert.status == AlertStatus.ACKNOWLEDGED && !alert.waitsForHuman -> stop(e.id, EscalationStatus.ACKNOWLEDGED)
                    else -> advance(e, alert, policy, manual = false)
                }
            }
        }
        return due.size
    }

    private suspend fun begin(alert: Alert, policy: EscalationPolicy, cur: Escalation?, manual: Boolean): Escalation? {
        val now = clock.now()
        val base = Escalation(alert.id, alert.teamId, policy.id, EscalationStatus.ACTIVE, 0, 0, null, cur?.pages.orEmpty(), now, now, (cur?.version ?: 0) + 1)
        return pageLevel(base, cur?.version, alert, policy, manual)
    }

    private suspend fun advance(cur: Escalation, alert: Alert, policy: EscalationPolicy, manual: Boolean): Escalation? {
        val now = clock.now()
        var level = cur.level + 1
        var cycle = cur.cycle
        if (level >= policy.levels.size) {
            if (cycle >= policy.repeat) {
                val done = cur.copy(status = EscalationStatus.EXHAUSTED, nextAt = null, updatedAt = now, version = cur.version + 1)
                if (!trySave(done, cur.version)) return null
                events.publish(DomainEvent("alert.escalation_exhausted", alert.id, now, listOf(alert.teamId), mapOf("policyId" to policy.id)))
                return done
            }
            level = 0
            cycle++
        }
        return pageLevel(cur.copy(level = level, cycle = cycle, updatedAt = now, version = cur.version + 1), cur.version, alert, policy, manual)
    }

    /** Saves the page first, then delivers it. A conflict means another instance paged this level: nothing is sent. */
    private suspend fun pageLevel(next: Escalation, expectedVersion: Long?, alert: Alert, policy: EscalationPolicy, manual: Boolean): Escalation? {
        val now = clock.now()
        val lvl = policy.levels[next.level]
        val recipients = resolve(lvl.targets, now)
        val page = EscalationPage(now, next.level + 1, next.cycle, recipients.map { it.id }, manual)
        val nextAt = if (recipients.isEmpty()) now else now.plus(Duration.ofMinutes(lvl.delayMinutes.toLong()))
        val saved = next.copy(nextAt = nextAt, pages = (next.pages + page).takeLast(100))
        if (!trySave(saved, expectedVersion)) return null
        events.publish(DomainEvent("alert.paged", alert.id, now, listOf(alert.teamId),
            mapOf("level" to page.level.toString(), "cycle" to page.cycle.toString(), "userIds" to page.userIds.joinToString(","))))
        if (recipients.isNotEmpty()) {
            runCatching {
                pager.page(PageNotice(alert.id, alert.title, "${publicUrl.trimEnd('/')}/alerts/${alert.id}", alert.severity, alert.teamId, policy.id, policy.name,
                    page.level, page.cycle, manual, recipients))
            }
        }
        return saved
    }

    private suspend fun resolve(targets: List<EscalationTarget>, at: Instant): List<PageRecipient> {
        val ids = LinkedHashSet<String>()
        for (t in targets) {
            when (t.kind) {
                TargetKind.USER -> ids += t.id
                TargetKind.SCHEDULE -> onCall.onCallFor(t.id, at)?.let { ids += it }
            }
        }
        return ids.mapNotNull { id -> users.get(id)?.takeIf { !it.disabled }?.let { PageRecipient(it.id, it.name, it.email) } }
    }

    private suspend fun stop(alertId: String, status: EscalationStatus) {
        repeat(3) {
            val cur = escalations.get(alertId) ?: return
            if (cur.status != EscalationStatus.ACTIVE) return
            if (trySave(cur.copy(status = status, nextAt = null, updatedAt = clock.now(), version = cur.version + 1), cur.version)) return
        }
    }

    private suspend fun trySave(e: Escalation, expectedVersion: Long?): Boolean = try {
        escalations.save(e, expectedVersion)
        true
    } catch (c: Conflict) {
        false
    }

    private suspend fun defaultPolicy(teamId: String) = policies.byTeam(teamId).firstOrNull { it.teamDefault && it.levels.isNotEmpty() }

    private suspend fun clearOtherDefaults(p: EscalationPolicy) {
        for (o in policies.byTeam(p.teamId)) {
            if (o.id == p.id || !o.teamDefault) continue
            runCatching { policies.save(o.copy(teamDefault = false, updatedAt = clock.now(), version = o.version + 1), o.version) }
        }
    }

    private fun requireOwner(by: Principal, team: Team) {
        Access.requireWrite(by)
        if (!Access.isTeamAdmin(by, team.id, team.projectId)) throw Forbidden("Only team owners change escalation policies")
    }

    private suspend fun validated(p: EscalationPolicy): EscalationPolicy {
        if (p.name.isBlank()) throw Invalid("Name is required")
        if (p.levels.isEmpty() || p.levels.size > MAX_LEVELS) throw Invalid("A policy has 1 to $MAX_LEVELS levels")
        if (p.repeat !in 0..MAX_REPEAT) throw Invalid("Repeat must be from 0 to $MAX_REPEAT")
        val maxDelay = settings.int(Keys.ESCALATION_MAX_ACK_MINUTES, listOf(p.teamId))
        p.levels.forEachIndexed { i, lvl ->
            val n = i + 1
            if (lvl.targets.isEmpty() || lvl.targets.size > MAX_TARGETS) throw Invalid("Level $n must have 1 to $MAX_TARGETS targets")
            if (lvl.delayMinutes < 1) throw Invalid("The delay of level $n must be at least 1 minute")
            if (lvl.delayMinutes > maxDelay) throw Invalid("The delay of level $n can be at most $maxDelay minutes (escalation.max_ack_minutes)")
            if (lvl.targets.size != lvl.targets.toSet().size) throw Invalid("Level $n has the same target twice")
            for (t in lvl.targets) {
                when (t.kind) {
                    TargetKind.SCHEDULE -> if (!onCall.exists(t.id)) throw Invalid("Unknown schedule ${t.id}")
                    TargetKind.USER -> {
                        val u = users.get(t.id) ?: throw Invalid("Unknown user ${t.id}")
                        if (u.disabled) throw Invalid("${u.name} is disabled")
                    }
                }
            }
        }
        return p
    }

    private suspend fun changed(teamId: String, policyId: String) =
        events.publish(DomainEvent("escalation.changed", policyId, clock.now(), listOf(teamId)))
}
