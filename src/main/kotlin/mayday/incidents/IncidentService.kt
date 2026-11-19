package mayday.incidents

import kotlinx.serialization.Serializable
import mayday.directory.DirectoryService
import mayday.identity.Access
import mayday.identity.Principal
import mayday.policy.Keys
import mayday.policy.PolicyReader
import mayday.shared.Actor
import mayday.shared.Clock
import mayday.shared.DomainEvent
import mayday.shared.EventPublisher
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Severity
import mayday.shared.Ts
import java.time.Duration
import java.time.Instant

@Serializable
data class NewIncident(
    val title: String,
    val description: String = "",
    val severity: Severity,
    val teamIds: List<String>,
    val commanderId: String? = null,
    val impactStartedAt: Ts? = null,
    val discoveredAt: Ts? = null,
    val labels: Map<String, String> = emptyMap(),
    val alertIds: List<String> = emptyList(),
)

@Serializable
data class IncidentPatch(
    val title: String? = null,
    val description: String? = null,
    val severity: Severity? = null,
    val status: IncidentStatus? = null,
    val teamIds: List<String>? = null,
    val commanderId: String? = null,
    val impactStartedAt: Ts? = null,
    val impactEndedAt: Ts? = null,
    val discoveredAt: Ts? = null,
    val labels: Map<String, String>? = null,
    val postmortemRequired: Boolean? = null,
    val version: Long? = null,
)

class IncidentService(
    private val incidents: IncidentRepository,
    private val timeline: TimelineRepository,
    private val directory: DirectoryService,
    private val policy: PolicyReader,
    private val events: EventPublisher,
    private val clock: Clock,
) {
    private val keyPattern = Regex("^([A-Za-z0-9]+)-(\\d+)$")

    /** Accepts an id, a key (INC-42) or a number (42). */
    suspend fun get(by: Principal, ref: String): Incident {
        Access.requireRead(by)
        return find(ref) ?: throw NotFound("Incident")
    }

    private suspend fun find(ref: String): Incident? {
        keyPattern.matchEntire(ref)?.let { return incidents.byNumber(it.groupValues[2].toLong()) }
        ref.toLongOrNull()?.let { return incidents.byNumber(it) }
        return incidents.get(ref)
    }

    suspend fun list(by: Principal, filter: IncidentFilter, cursor: String?, limit: Int) =
        Access.requireRead(by).let { incidents.list(filter, cursor, limit.coerceIn(1, 200)) }

    suspend fun timeline(by: Principal, ref: String): List<TimelineEntry> = timeline.list(get(by, ref).id)

    suspend fun declare(by: Principal, input: NewIncident): Incident {
        Access.requireWrite(by)
        return create(by.actor, input)
    }

    /** Used by the alerting context; the trigger is a policy decision, not a user action. */
    suspend fun declareFromAlert(input: NewIncident): Incident = create(Actor.system, input)

    private suspend fun create(actor: Actor, input: NewIncident): Incident {
        val teams = input.teamIds.distinct().map { directory.team(it).id }
        val now = clock.now()
        val number = incidents.nextNumber()
        val prefix = policy.string(Keys.INCIDENT_PREFIX)
        val incident = Incident(
            id = Ids.new(),
            number = number,
            key = "$prefix-$number",
            title = input.title.trim(),
            description = input.description,
            severity = input.severity,
            teamIds = teams,
            commanderId = input.commanderId,
            impactStartedAt = input.impactStartedAt,
            discoveredAt = input.discoveredAt ?: now,
            reportedAt = now,
            postmortemRequired = postmortemRequired(input.severity, teams),
            alertIds = input.alertIds,
            labels = input.labels,
            createdBy = actor,
            createdAt = now,
            updatedAt = now,
        )
        incidents.save(incident, null)
        record(incident, EntryKind.STATUS, actor, "Incident declared as ${incident.severity} with status ${incident.status}.")
        publish("incident.created", incident)
        return incident
    }

    suspend fun update(by: Principal, ref: String, patch: IncidentPatch): Incident {
        Access.requireWrite(by)
        val cur = get(by, ref)
        val now = clock.now()
        val teams = patch.teamIds?.distinct()?.map { directory.team(it).id } ?: cur.teamIds
        val severity = patch.severity ?: cur.severity
        val status = patch.status ?: cur.status
        val resolvedNow = !status.open && cur.status.open
        val reopened = status.open && !cur.status.open
        val required = when {
            patch.postmortemRequired != null -> {
                if (!patch.postmortemRequired && postmortemRequired(severity, teams)) {
                    throw Invalid("Policy makes a postmortem mandatory for this incident")
                }
                patch.postmortemRequired
            }
            else -> cur.postmortemRequired || postmortemRequired(severity, teams)
        }
        val resolvedAt = when {
            resolvedNow -> now
            reopened -> null
            else -> cur.resolvedAt
        }
        val next = cur.copy(
            title = patch.title?.trim() ?: cur.title,
            description = patch.description ?: cur.description,
            severity = severity,
            status = status,
            teamIds = teams,
            commanderId = patch.commanderId ?: cur.commanderId,
            impactStartedAt = patch.impactStartedAt ?: cur.impactStartedAt,
            impactEndedAt = patch.impactEndedAt ?: (if (resolvedNow && cur.impactEndedAt == null) now else cur.impactEndedAt),
            discoveredAt = patch.discoveredAt ?: cur.discoveredAt,
            acknowledgedAt = cur.acknowledgedAt ?: if (status != IncidentStatus.INVESTIGATING) now else null,
            resolvedAt = resolvedAt,
            labels = patch.labels ?: cur.labels,
            postmortemRequired = required,
            postmortemDueAt = if (required && resolvedAt != null) {
                cur.postmortemDueAt ?: resolvedAt.plus(Duration.ofDays(policy.int(Keys.POSTMORTEM_DUE_DAYS, teams).toLong()))
            } else if (resolvedAt == null) null else cur.postmortemDueAt,
            updatedAt = now,
            version = cur.version + 1,
        )
        incidents.save(next, patch.version ?: cur.version)
        if (next.status != cur.status) record(next, EntryKind.STATUS, by.actor, "Status changed from ${cur.status} to ${next.status}.")
        if (next.severity != cur.severity) record(next, EntryKind.SEVERITY, by.actor, "Severity changed from ${cur.severity} to ${next.severity}.")
        val fields = buildList {
            if (next.title != cur.title) add("title")
            if (next.description != cur.description) add("description")
            if (next.teamIds != cur.teamIds) add("responder teams")
            if (next.impactStartedAt != cur.impactStartedAt) add("impact start")
            if (next.impactEndedAt != cur.impactEndedAt && !resolvedNow) add("impact end")
            if (next.discoveredAt != cur.discoveredAt) add("discovery time")
            if (next.commanderId != cur.commanderId) add("commander")
        }
        if (fields.isNotEmpty()) record(next, EntryKind.FIELD, by.actor, "Updated ${fields.joinToString(", ")}.")
        publish("incident.updated", next)
        return next
    }

    suspend fun addEntry(by: Principal, ref: String, text: String, kind: EntryKind = EntryKind.NOTE, at: Instant? = null, data: Map<String, String> = emptyMap()): TimelineEntry {
        Access.requireWrite(by)
        if (text.isBlank()) throw Invalid("Text is required")
        val inc = get(by, ref)
        val effectiveKind = if (by.isAgent && kind == EntryKind.NOTE) EntryKind.AGENT else kind
        val e = record(inc, effectiveKind, by.actor, text.trim(), at, data)
        publish("incident.timeline", inc)
        return e
    }

    suspend fun systemEntry(incidentId: String, kind: EntryKind, actor: Actor, text: String, data: Map<String, String> = emptyMap()) {
        val inc = incidents.get(incidentId) ?: return
        record(inc, kind, actor, text, null, data)
        publish("incident.timeline", inc)
    }

    suspend fun linkAlert(incidentId: String, alertId: String) {
        val cur = incidents.get(incidentId) ?: return
        if (alertId in cur.alertIds) return
        val next = cur.copy(alertIds = cur.alertIds + alertId, updatedAt = clock.now(), version = cur.version + 1)
        incidents.save(next, cur.version)
        publish("incident.updated", next)
    }

    suspend fun savePostmortem(by: Principal, ref: String, pm: Postmortem): Incident {
        Access.requireWrite(by)
        val cur = get(by, ref)
        val next = cur.copy(
            postmortem = pm.copy(updatedBy = by.actor, updatedAt = clock.now()),
            updatedAt = clock.now(),
            version = cur.version + 1,
        )
        incidents.save(next, cur.version)
        val verb = if (cur.postmortem == null) "created" else "updated"
        record(next, EntryKind.POSTMORTEM, by.actor, "Postmortem $verb (${pm.status}).")
        publish("incident.updated", next)
        return next
    }

    suspend fun saveResolution(by: Principal, ref: String, notes: String): Incident {
        Access.requireWrite(by)
        val cur = get(by, ref)
        val next = cur.copy(resolutionNotes = notes, updatedAt = clock.now(), version = cur.version + 1)
        incidents.save(next, cur.version)
        if (cur.resolutionNotes.isBlank() && notes.isNotBlank()) {
            record(next, EntryKind.RESOLUTION, by.actor, "Resolution notes added.")
        }
        publish("incident.updated", next)
        return next
    }

    suspend fun saveRunbook(by: Principal, ref: String, title: String, body: String): Incident {
        Access.requireWrite(by)
        if (body.isBlank()) throw Invalid("Runbook body is required")
        val cur = get(by, ref)
        val next = cur.copy(
            runbook = Runbook(title.ifBlank { "Runbook: ${cur.title}" }, body, by.actor, clock.now()),
            updatedAt = clock.now(),
            version = cur.version + 1,
        )
        incidents.save(next, cur.version)
        record(next, EntryKind.RUNBOOK, by.actor, "Runbook generated.")
        publish("incident.updated", next)
        return next
    }

    private suspend fun postmortemRequired(severity: Severity, teams: List<String>): Boolean {
        val min = policy.severity(Keys.POSTMORTEM_MIN_SEVERITY, teams) ?: return false
        return severity.atLeast(min)
    }

    private suspend fun record(inc: Incident, kind: EntryKind, actor: Actor, text: String, at: Instant? = null, data: Map<String, String> = emptyMap()): TimelineEntry {
        val e = TimelineEntry(Ids.new(), inc.id, at ?: clock.now(), kind, actor, text, data)
        timeline.append(e)
        return e
    }

    private suspend fun publish(type: String, inc: Incident) =
        events.publish(DomainEvent(type, inc.id, clock.now(), inc.teamIds, mapOf("key" to inc.key, "severity" to inc.severity.name)))
}
