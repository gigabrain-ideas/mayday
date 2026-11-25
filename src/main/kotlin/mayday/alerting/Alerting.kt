package mayday.alerting

import kotlinx.serialization.Serializable
import mayday.directory.DirectoryService
import mayday.identity.Access
import mayday.identity.Principal
import mayday.identity.TokenScope
import mayday.incidents.EntryKind
import mayday.incidents.IncidentService
import mayday.incidents.NewIncident
import mayday.policy.Keys
import mayday.policy.PolicyReader
import mayday.shared.Actor
import mayday.shared.ActorKind
import mayday.shared.Clock
import mayday.shared.Crypto
import mayday.shared.DomainEvent
import mayday.shared.EventPublisher
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Page
import mayday.shared.Severity
import mayday.shared.SpanScope
import mayday.shared.Telemetry
import mayday.shared.Ts
import java.time.Duration
import java.time.Instant

@Serializable
enum class AlertStatus { FIRING, ACKNOWLEDGED, RESOLVED }

@Serializable
data class AlertUpdate(val at: Ts, val actor: Actor, val text: String)

@Serializable
data class Alert(
    val id: String,
    val fingerprint: String,
    val source: String,
    val title: String,
    val description: String = "",
    val severity: Severity,
    val status: AlertStatus = AlertStatus.FIRING,
    val teamId: String,
    val labels: Map<String, String> = emptyMap(),
    val externalUrl: String? = null,
    val incidentId: String? = null,
    val firstSeenAt: Ts,
    val lastSeenAt: Ts,
    val count: Long = 1,
    val acknowledgedBy: Actor? = null,
    val resolvedAt: Ts? = null,
    val updates: List<AlertUpdate> = emptyList(),
    val version: Long = 1,
)

@Serializable
data class AlertSignal(
    val title: String,
    val severity: Severity = Severity.SEV3,
    val team: String,
    val source: String = "api",
    val fingerprint: String? = null,
    val status: AlertStatus = AlertStatus.FIRING,
    val description: String = "",
    val labels: Map<String, String> = emptyMap(),
    val externalUrl: String? = null,
)

data class AlertFilter(
    val statuses: Set<AlertStatus> = emptySet(),
    val severities: Set<Severity> = emptySet(),
    val teamIds: Set<String> = emptySet(),
    val query: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

interface AlertRepository {
    suspend fun get(id: String): Alert?
    suspend fun latestByFingerprint(fingerprint: String): Alert?
    suspend fun save(alert: Alert, expectedVersion: Long?)
    suspend fun list(filter: AlertFilter, cursor: String?, limit: Int): Page<Alert>
    suspend fun scan(filter: AlertFilter, consumer: (Alert) -> Unit)
}

/** Port that the agents context implements to react to new alerts. */
interface AlertAutomation {
    suspend fun onAlertCreated(alert: Alert)
}

/** Port that the escalation context implements: an alert starts or stops needing a human. */
interface AlertLifecycle {
    /** A new alert, or a resolved alert that fires again. */
    suspend fun opened(alert: Alert)

    /** The alert was acknowledged or resolved. */
    suspend fun closed(alert: Alert)

    companion object {
        val none = object : AlertLifecycle {
            override suspend fun opened(alert: Alert) = Unit
            override suspend fun closed(alert: Alert) = Unit
        }
    }
}

@Serializable
data class IngestResult(val alert: Alert, val created: Boolean, val incidentKey: String? = null)

class AlertService(
    private val alerts: AlertRepository,
    private val incidents: IncidentService,
    private val directory: DirectoryService,
    private val policy: PolicyReader,
    private val events: EventPublisher,
    private val clock: Clock,
    private val telemetry: Telemetry = Telemetry.noop,
) {
    @Volatile var automation: AlertAutomation = object : AlertAutomation { override suspend fun onAlertCreated(alert: Alert) = Unit }
    @Volatile var lifecycle: AlertLifecycle = AlertLifecycle.none

    suspend fun get(by: Principal, id: String): Alert = Access.requireRead(by).let { alerts.get(id) ?: throw NotFound("Alert") }

    suspend fun list(by: Principal, filter: AlertFilter, cursor: String?, limit: Int) =
        Access.requireRead(by).let { alerts.list(filter, cursor, limit.coerceIn(1, 200)) }

    suspend fun ingest(by: Principal, signal: AlertSignal): IngestResult =
        telemetry.span("alert.ingest", mapOf("mayday.alert.source" to signal.source, "mayday.alert.severity" to signal.severity.name, "mayday.alert.status" to signal.status.name)) { span ->
            ingest(by, signal, span).also { r ->
                span.set("mayday.alert.id", r.alert.id)
                span.set("mayday.alert.team_id", r.alert.teamId)
                span.set("mayday.alert.count", r.alert.count)
                span.set("mayday.incident.key", r.incidentKey)
            }
        }

    /** A token with only ALERTS_INGEST sends alerts for the teams of its owner. Agent tokens and administrators are not limited. */
    private fun requireIngestTeam(by: Principal, teamId: String, projectId: String?) {
        val u = by.user ?: return
        if (u.orgRole == mayday.identity.OrgRole.ADMIN || u.teams.containsKey(teamId) || (projectId != null && projectId in u.projectAdmin)) return
        throw Forbidden("This token can send alerts only for the teams of its owner")
    }

    private suspend fun ingest(by: Principal, signal: AlertSignal, span: SpanScope): IngestResult {
        if (!by.has(TokenScope.ALERTS_INGEST) && !by.has(TokenScope.WRITE)) throw Forbidden("The token cannot send alerts")
        if (signal.title.isBlank()) throw Invalid("Alert title is required")
        val team = directory.team(signal.team)
        if (!by.has(TokenScope.WRITE)) requireIngestTeam(by, team.id, team.projectId)
        val now = clock.now()
        val fp = signal.fingerprint?.takeIf { it.isNotBlank() } ?: fingerprint(signal.source, signal.title, team.id, signal.labels)
        val window = Duration.ofMinutes(policy.int(Keys.ALERT_DEDUP_MINUTES, listOf(team.id)).toLong())
        val latest = alerts.latestByFingerprint(fp)
        val active = latest?.takeIf { it.status != AlertStatus.RESOLVED }
        val sourceActor = Actor(ActorKind.SYSTEM, signal.source, signal.source)

        if (signal.status == AlertStatus.RESOLVED) {
            span.set(OUTCOME, "resolved")
            if (active != null) return IngestResult(resolveInternal(active, sourceActor, "Resolved by ${signal.source}."), false)
            return IngestResult(latest ?: resolveInternal(createAlert(signal, fp, team.id, now), sourceActor, "Resolved by ${signal.source}."), latest == null)
        }

        val flapping = latest?.takeIf { it.status == AlertStatus.RESOLVED && it.resolvedAt?.plus(window)?.isAfter(now) == true }
        val target = active ?: flapping
        if (target != null) {
            val reopened = target.status == AlertStatus.RESOLVED
            val next = target.copy(
                status = if (reopened) AlertStatus.FIRING else target.status,
                resolvedAt = if (reopened) null else target.resolvedAt,
                lastSeenAt = now,
                count = target.count + 1,
                severity = if (signal.severity.rank < target.severity.rank) signal.severity else target.severity,
                labels = target.labels + signal.labels,
                updates = if (reopened) target.updates + AlertUpdate(now, sourceActor, "Firing again.") else target.updates,
                version = target.version + 1,
            )
            alerts.save(next, target.version)
            span.set(OUTCOME, if (reopened) "reopened" else "deduplicated")
            if (reopened) next.incidentId?.let { incidentTimeline(it, sourceActor, "Alert firing again: ${next.title}") }
            publish("alert.updated", next)
            if (reopened) runCatching { lifecycle.opened(next) }
            return IngestResult(next, false)
        }

        val created = createAlert(signal, fp, team.id, now)
        span.set(OUTCOME, "created")
        var result = created
        var incidentKey: String? = null
        val threshold = policy.severity(Keys.ALERT_AUTO_INCIDENT_SEVERITY, listOf(team.id))
        if (threshold != null && created.severity.atLeast(threshold)) {
            val inc = incidents.declareFromAlert(
                NewIncident(
                    title = created.title,
                    description = created.description,
                    severity = created.severity,
                    teamIds = listOf(team.id),
                    discoveredAt = created.firstSeenAt,
                    impactStartedAt = created.firstSeenAt,
                    labels = created.labels,
                    alertIds = listOf(created.id),
                ),
            )
            result = created.copy(incidentId = inc.id, version = created.version + 1)
            alerts.save(result, created.version)
            incidentKey = inc.key
            incidents.systemEntry(inc.id, EntryKind.ALERT, Actor.system, "Alert from ${created.source}: ${created.title}", mapOf("alertId" to created.id))
        }
        publish("alert.created", result)
        runCatching { lifecycle.opened(result) }
        runCatching { automation.onAlertCreated(result) }
        return IngestResult(result, true, incidentKey)
    }

    private suspend fun createAlert(signal: AlertSignal, fp: String, teamId: String, now: Instant): Alert {
        val a = Alert(
            id = Ids.new(),
            fingerprint = fp,
            source = signal.source.ifBlank { "api" },
            title = signal.title.trim(),
            description = signal.description,
            severity = signal.severity,
            teamId = teamId,
            labels = signal.labels,
            externalUrl = signal.externalUrl,
            firstSeenAt = now,
            lastSeenAt = now,
        )
        alerts.save(a, null)
        return a
    }

    suspend fun acknowledge(by: Principal, id: String): Alert {
        Access.requireWrite(by)
        val cur = get(by, id)
        if (cur.status != AlertStatus.FIRING) return cur
        val next = cur.copy(
            status = AlertStatus.ACKNOWLEDGED,
            acknowledgedBy = by.actor,
            updates = cur.updates + AlertUpdate(clock.now(), by.actor, "Acknowledged."),
            version = cur.version + 1,
        )
        alerts.save(next, cur.version)
        publish("alert.updated", next)
        runCatching { lifecycle.closed(next) }
        return next
    }

    suspend fun resolve(by: Principal, id: String, note: String = ""): Alert {
        Access.requireWrite(by)
        val cur = get(by, id)
        if (cur.status == AlertStatus.RESOLVED) return cur
        return resolveInternal(cur, by.actor, note.ifBlank { "Resolved." })
    }

    private suspend fun resolveInternal(cur: Alert, actor: Actor, text: String): Alert {
        val now = clock.now()
        val next = cur.copy(
            status = AlertStatus.RESOLVED,
            resolvedAt = now,
            updates = cur.updates + AlertUpdate(now, actor, text),
            version = cur.version + 1,
        )
        alerts.save(next, cur.version)
        next.incidentId?.let { incidentTimeline(it, actor, "Alert resolved: ${next.title}") }
        publish("alert.updated", next)
        runCatching { lifecycle.closed(next) }
        return next
    }

    suspend fun addUpdate(by: Principal, id: String, text: String): Alert {
        Access.requireWrite(by)
        if (text.isBlank()) throw Invalid("Text is required")
        val cur = get(by, id)
        val next = cur.copy(updates = cur.updates + AlertUpdate(clock.now(), by.actor, text.trim()), version = cur.version + 1)
        alerts.save(next, cur.version)
        next.incidentId?.let { incidentTimeline(it, by.actor, "Alert update (${next.title}): ${text.trim()}") }
        publish("alert.updated", next)
        return next
    }

    suspend fun linkIncident(by: Principal, id: String, incidentRef: String): Alert {
        Access.requireWrite(by)
        val cur = get(by, id)
        val inc = incidents.get(by, incidentRef)
        val next = cur.copy(incidentId = inc.id, version = cur.version + 1)
        alerts.save(next, cur.version)
        incidents.linkAlert(inc.id, cur.id)
        incidents.systemEntry(inc.id, EntryKind.ALERT, by.actor, "Alert linked: ${cur.title}", mapOf("alertId" to cur.id))
        publish("alert.updated", next)
        return next
    }

    private suspend fun incidentTimeline(incidentId: String, actor: Actor, text: String) {
        val inc = runCatching { incidents.get(mayday.identity.Principal.system, incidentId) }.getOrNull() ?: return
        if (policy.bool(Keys.TIMELINE_AUTO, inc.teamIds)) incidents.systemEntry(incidentId, EntryKind.ALERT, actor, text)
    }

    private suspend fun publish(type: String, a: Alert) =
        events.publish(DomainEvent(type, a.id, clock.now(), listOf(a.teamId), mapOf("severity" to a.severity.name, "status" to a.status.name)))

    companion object {
        private const val OUTCOME = "mayday.alert.outcome"

        fun fingerprint(source: String, title: String, teamId: String, labels: Map<String, String>): String {
            val canonical = buildString {
                append(source).append('|').append(title).append('|').append(teamId)
                labels.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
            }
            return Crypto.sha256Hex(canonical).take(32)
        }
    }
}
