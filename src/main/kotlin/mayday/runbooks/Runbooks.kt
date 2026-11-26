package mayday.runbooks

import kotlinx.serialization.Serializable
import mayday.alerting.Alert
import mayday.audit.AuditCategory
import mayday.audit.AuditTarget
import mayday.audit.AuditTrail
import mayday.audit.record
import mayday.directory.DirectoryService
import mayday.identity.Access
import mayday.identity.Principal
import mayday.identity.audit
import mayday.shared.Actor
import mayday.shared.Clock
import mayday.shared.Conflict
import mayday.shared.DomainEvent
import mayday.shared.EventPublisher
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Ts

@Serializable
enum class RunbookStatus { DRAFT, APPROVED, REVOKED }

/** The type of alert that a runbook covers. Every given criterion must hold. An empty criterion matches all alerts. */
@Serializable
data class AlertMatch(
    val source: String = "",
    val labels: Map<String, String> = emptyMap(),
    /** Fingerprint pattern. `*` matches any text. */
    val fingerprint: String = "",
) {
    val criteria: Int get() = (if (source.isNotBlank()) 1 else 0) + labels.size + (if (fingerprint.isNotBlank()) 1 else 0)

    fun matches(alert: Alert): Boolean =
        (source.isBlank() || source.equals(alert.source, ignoreCase = true)) &&
            labels.all { (k, v) -> alert.labels[k] == v } &&
            (fingerprint.isBlank() || glob(fingerprint).matches(alert.fingerprint))

    private fun glob(p: String) = Regex(p.split('*').joinToString(".*") { Regex.escape(it) })
}

/**
 * A standard operating procedure that a person approved for agentic use on one type of alert.
 * [allowedTools]: the write tools that an agent can call while it follows the runbook. `resolve_alert` lets it resolve the alert.
 */
@Serializable
data class Runbook(
    val id: String,
    val teamId: String,
    val title: String,
    val body: String,
    val match: AlertMatch,
    val allowedTools: List<String> = emptyList(),
    val status: RunbookStatus = RunbookStatus.DRAFT,
    val approvedBy: Actor? = null,
    val approvedAt: Ts? = null,
    val revokedBy: Actor? = null,
    val revokedAt: Ts? = null,
    val createdBy: Actor,
    val createdAt: Ts,
    val updatedAt: Ts,
    val version: Long = 1,
) {
    val approved: Boolean get() = status == RunbookStatus.APPROVED

    fun allows(tool: String) = tool in allowedTools || tool in READ_TOOLS

    fun covers(alert: Alert) = approved && alert.teamId == teamId && match.matches(alert)

    companion object {
        const val RESOLVE = "resolve_alert"

        /** Mayday tools that only read. An agent that follows a runbook can always call them. */
        val READ_TOOLS = setOf("list_incidents", "get_incident", "list_alerts", "get_alert", "list_teams", "get_on_call", "get_settings", "list_runbooks", "get_runbook")
    }
}

@Serializable
data class RunbookInput(
    val teamId: String = "",
    val title: String,
    val body: String,
    val match: AlertMatch = AlertMatch(),
    val allowedTools: List<String> = emptyList(),
)

interface RunbookRepository {
    suspend fun get(id: String): Runbook?
    suspend fun byTeam(teamId: String): List<Runbook>
    suspend fun save(runbook: Runbook, expectedVersion: Long?)
    suspend fun delete(id: String)
}

private const val MAX_TOOLS = 50
private val TOOL_NAME = Regex("^[A-Za-z0-9_.:-]{1,64}$")

class RunbookService(
    private val runbooks: RunbookRepository,
    private val directory: DirectoryService,
    private val events: EventPublisher,
    private val clock: Clock,
    private val audit: AuditTrail = AuditTrail.none,
) {
    suspend fun list(by: Principal, teamRef: String): List<Runbook> {
        Access.requireRead(by)
        return runbooks.byTeam(directory.team(teamRef).id).sortedBy { it.title.lowercase() }
    }

    suspend fun get(by: Principal, id: String): Runbook = Access.requireRead(by).let { runbooks.get(id) ?: throw NotFound("Runbook") }

    /** Everyone with write access drafts runbooks, also agents. A new runbook is a DRAFT. */
    suspend fun create(by: Principal, input: RunbookInput): Runbook {
        Access.requireWrite(by)
        val team = directory.team(input.teamId)
        val now = clock.now()
        val rb = validated(Runbook(Ids.new(), team.id, input.title.trim(), input.body.trim(), input.match.trimmed(), input.allowedTools.map { it.trim() }.distinct(),
            createdBy = by.actor, createdAt = now, updatedAt = now))
        runbooks.save(rb, null)
        changed("runbook.created", by, rb)
        return rb
    }

    /** A change of an approved runbook needs a team owner and makes it a DRAFT again: a person must approve the new text. */
    suspend fun update(by: Principal, id: String, input: RunbookInput, expectedVersion: Long?): Runbook {
        Access.requireWrite(by)
        val cur = runbooks.get(id) ?: throw NotFound("Runbook")
        if (cur.approved) requireOwner(by, cur.teamId)
        if (expectedVersion != null && expectedVersion != cur.version) throw Conflict("The runbook changed. Reload and try again.")
        val next = validated(cur.copy(title = input.title.trim(), body = input.body.trim(), match = input.match.trimmed(),
            allowedTools = input.allowedTools.map { it.trim() }.distinct(), status = RunbookStatus.DRAFT, approvedBy = null, approvedAt = null,
            updatedAt = clock.now(), version = cur.version + 1))
        runbooks.save(next, cur.version)
        changed("runbook.updated", by, next)
        return next
    }

    /**
     * A team owner approves the runbook for agentic use. Only a person decides: agents, delegated tokens and the system cannot.
     * [expectedVersion] is the version that the person read, so nobody approves a text that they did not see.
     */
    suspend fun approve(by: Principal, id: String, expectedVersion: Long): Runbook {
        val cur = runbooks.get(id) ?: throw NotFound("Runbook")
        requireOwner(by, cur.teamId)
        if (by.user == null || by.isAgent) throw Forbidden("Only a person can approve a runbook for agentic use")
        if (expectedVersion != cur.version) throw Conflict("The runbook changed. Read it again before you approve it.")
        if (cur.approved) throw Conflict("The runbook is already approved")
        val now = clock.now()
        val next = cur.copy(status = RunbookStatus.APPROVED, approvedBy = by.actor, approvedAt = now, revokedBy = null, revokedAt = null,
            updatedAt = now, version = cur.version + 1)
        runbooks.save(next, cur.version)
        changed("runbook.approved", by, next)
        return next
    }

    suspend fun revoke(by: Principal, id: String): Runbook {
        val cur = runbooks.get(id) ?: throw NotFound("Runbook")
        requireOwner(by, cur.teamId)
        if (!cur.approved) throw Conflict("The runbook is not approved")
        val now = clock.now()
        val next = cur.copy(status = RunbookStatus.REVOKED, revokedBy = by.actor, revokedAt = now, updatedAt = now, version = cur.version + 1)
        runbooks.save(next, cur.version)
        changed("runbook.revoked", by, next)
        return next
    }

    /** The approved runbook of the alert's team that covers the alert. The most specific match wins, then the latest approval. */
    suspend fun matchFor(alert: Alert): Runbook? = runbooks.byTeam(alert.teamId)
        .filter { it.covers(alert) }
        .maxWithOrNull(compareBy<Runbook>({ it.match.criteria }, { it.approvedAt }))

    /** The runbook, when it is still approved. A change or a revoke ends the agentic use at once. */
    suspend fun approved(id: String): Runbook? = runbooks.get(id)?.takeIf { it.approved }

    private suspend fun requireOwner(by: Principal, teamId: String) {
        Access.requireWrite(by)
        val team = directory.teamOrNull(teamId)
        if (!Access.isTeamAdmin(by, teamId, team?.projectId)) throw Forbidden("Only team owners approve, revoke or change approved runbooks")
    }

    private fun AlertMatch.trimmed() = AlertMatch(source.trim(), labels.mapKeys { it.key.trim() }.mapValues { it.value.trim() }, fingerprint.trim())

    private fun validated(rb: Runbook): Runbook {
        if (rb.title.isBlank()) throw Invalid("Title is required")
        if (rb.body.isBlank()) throw Invalid("The procedure is required")
        if (rb.match.criteria == 0) throw Invalid("Name the type of alert: a source, labels or a fingerprint pattern")
        if (rb.match.labels.keys.any { it.isBlank() }) throw Invalid("A label needs a name")
        if (rb.allowedTools.size > MAX_TOOLS) throw Invalid("A runbook allows at most $MAX_TOOLS tools")
        rb.allowedTools.firstOrNull { !TOOL_NAME.matches(it) }?.let { throw Invalid("'$it' is not a tool name") }
        return rb
    }

    private suspend fun changed(type: String, by: Principal, rb: Runbook) {
        audit.record(AuditCategory.AGENT, type, by.audit(), AuditTarget("runbook", rb.id, rb.title),
            mapOf("team" to rb.teamId, "status" to rb.status.name, "version" to rb.version.toString(), "tools" to rb.allowedTools.joinToString(",")), listOf(rb.teamId))
        events.publish(DomainEvent(type, rb.id, clock.now(), listOf(rb.teamId), mapOf("status" to rb.status.name)))
    }
}
