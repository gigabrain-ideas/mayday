package mayday.incidents

import kotlinx.serialization.Serializable
import mayday.shared.Actor
import mayday.shared.Invalid
import mayday.shared.Page
import mayday.shared.Severity
import mayday.shared.Ts
import java.time.Instant

@Serializable
enum class IncidentStatus(val open: Boolean) {
    INVESTIGATING(true), IDENTIFIED(true), MONITORING(true), RESOLVED(false), CLOSED(false)
}

@Serializable
enum class PostmortemStatus { DRAFT, IN_REVIEW, PUBLISHED }

@Serializable
data class ActionItem(val text: String, val owner: String = "", val done: Boolean = false)

@Serializable
data class Postmortem(
    val status: PostmortemStatus = PostmortemStatus.DRAFT,
    val summary: String = "",
    val impact: String = "",
    val rootCause: String = "",
    val detection: String = "",
    val resolution: String = "",
    val lessons: String = "",
    val actionItems: List<ActionItem> = emptyList(),
    val updatedBy: Actor? = null,
    val updatedAt: Ts? = null,
)

@Serializable
data class Runbook(
    val title: String,
    val body: String,
    val generatedBy: Actor,
    val createdAt: Ts,
)

@Serializable
data class Incident(
    val id: String,
    val number: Long,
    val key: String,
    val title: String,
    val description: String = "",
    val severity: Severity,
    val status: IncidentStatus = IncidentStatus.INVESTIGATING,
    val teamIds: List<String>,
    val commanderId: String? = null,
    val impactStartedAt: Ts? = null,
    val impactEndedAt: Ts? = null,
    val discoveredAt: Ts? = null,
    val reportedAt: Ts,
    val acknowledgedAt: Ts? = null,
    val resolvedAt: Ts? = null,
    val postmortemRequired: Boolean = false,
    val postmortemDueAt: Ts? = null,
    val postmortem: Postmortem? = null,
    val resolutionNotes: String = "",
    val runbook: Runbook? = null,
    val alertIds: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val createdBy: Actor,
    val createdAt: Ts,
    val updatedAt: Ts,
    val version: Long = 1,
) {
    init {
        if (title.isBlank()) throw Invalid("Title is required")
        if (teamIds.isEmpty()) throw Invalid("At least one responder team is required")
        if (impactStartedAt != null && impactEndedAt != null && impactEndedAt.isBefore(impactStartedAt)) {
            throw Invalid("Impact end must be after impact start")
        }
    }

    val isOpen: Boolean get() = status.open
}

@Serializable
enum class EntryKind { NOTE, STATUS, SEVERITY, FIELD, ALERT, AGENT, POSTMORTEM, RUNBOOK, RESOLUTION }

@Serializable
data class TimelineEntry(
    val id: String,
    val incidentId: String,
    val at: Ts,
    val kind: EntryKind,
    val actor: Actor,
    val text: String,
    val data: Map<String, String> = emptyMap(),
)

data class IncidentFilter(
    val statuses: Set<IncidentStatus> = emptySet(),
    val severities: Set<Severity> = emptySet(),
    val teamIds: Set<String> = emptySet(),
    val query: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

interface IncidentRepository {
    /** Allocate the next monotonic incident number. Never reuses a number. */
    fun nextNumber(): Long
    fun get(id: String): Incident?
    fun byNumber(number: Long): Incident?

    /** Optimistic concurrency: fails with Conflict when the stored version differs from [expectedVersion]. */
    fun save(incident: Incident, expectedVersion: Long?)

    /** Newest first. Cursor is opaque to callers. */
    fun list(filter: IncidentFilter, cursor: String?, limit: Int): Page<Incident>

    /** All incidents reported in [from, to) that match the filter; used for reports. */
    fun scan(filter: IncidentFilter, consumer: (Incident) -> Unit)
}

interface TimelineRepository {
    fun append(entry: TimelineEntry)
    fun list(incidentId: String): List<TimelineEntry>
}
