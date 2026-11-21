package mayday.policy

import kotlinx.serialization.Serializable
import mayday.directory.DirectoryService
import mayday.identity.Access
import mayday.identity.Principal
import mayday.shared.Actor
import mayday.shared.Clock
import mayday.shared.DomainEvent
import mayday.shared.DomainException
import mayday.shared.EventPublisher
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.NotFound
import mayday.shared.Severity
import mayday.shared.Ts

@Serializable
enum class Scope { ORG, PROJECT, TEAM }

@Serializable
enum class Enforcement { NONE, MINIMUM, LOCKED }

@Serializable
enum class SettingType { BOOL, INT, SEVERITY, STRING }

/** Which direction of a value is stricter. MINIMUM enforcement lets lower scopes move only in that direction. */
@Serializable
enum class Strictness { HIGHER, LOWER, TRUE, FALSE, NONE }

@Serializable
data class SettingDef(
    val key: String,
    val label: String,
    val description: String,
    val type: SettingType,
    val default: String,
    val scopes: Set<Scope>,
    val strictness: Strictness,
    val min: Int? = null,
    val max: Int? = null,
)

object Keys {
    const val POSTMORTEM_MIN_SEVERITY = "postmortem.required_min_severity"
    const val POSTMORTEM_DUE_DAYS = "postmortem.due_days"
    const val ALERT_AUTO_INCIDENT_SEVERITY = "alerts.auto_incident_min_severity"
    const val ALERT_DEDUP_MINUTES = "alerts.dedup_window_minutes"
    const val AGENT_AUTO_ANALYSIS = "agents.auto_analysis"
    const val AGENT_AUTO_REMEDIATION = "agents.auto_remediation"
    const val AGENT_PATCH_APPROVAL = "agents.patch_requires_approval"
    const val TIMELINE_AUTO = "timeline.auto_populate"
    const val MCP_WRITE = "mcp.write_enabled"
    const val AGENT_EXCHANGE_ADMIN = "agents.exchange_admin"
    const val INCIDENT_PREFIX = "incident.key_prefix"
}

/** "NONE" disables a severity threshold. */
const val SEVERITY_NONE = "NONE"

object SettingCatalog {
    private val all3 = setOf(Scope.ORG, Scope.PROJECT, Scope.TEAM)

    val defs: List<SettingDef> = listOf(
        SettingDef(Keys.POSTMORTEM_MIN_SEVERITY, "Postmortem required from severity",
            "A postmortem is mandatory for incidents of this severity or worse. NONE makes postmortems optional.",
            SettingType.SEVERITY, "SEV2", all3, Strictness.HIGHER),
        SettingDef(Keys.POSTMORTEM_DUE_DAYS, "Postmortem due (days)",
            "Number of days after resolution until a mandatory postmortem is due.",
            SettingType.INT, "7", all3, Strictness.LOWER, 1, 90),
        SettingDef(Keys.ALERT_AUTO_INCIDENT_SEVERITY, "Open incident from alert severity",
            "A firing alert of this severity or worse opens an incident. NONE turns this off.",
            SettingType.SEVERITY, "SEV2", all3, Strictness.HIGHER),
        SettingDef(Keys.ALERT_DEDUP_MINUTES, "Alert deduplication window (minutes)",
            "A resolved alert that fires again inside this window reopens instead of creating a new alert.",
            SettingType.INT, "60", all3, Strictness.NONE, 1, 10080),
        SettingDef(Keys.AGENT_AUTO_ANALYSIS, "Automatic agent analysis",
            "Agents with an alert or incident trigger start an analysis without a human.",
            SettingType.BOOL, "true", all3, Strictness.NONE),
        SettingDef(Keys.AGENT_AUTO_REMEDIATION, "Automatic agent remediation",
            "Agents can try a correction without a human. Keep this off unless the remediation is safe to repeat.",
            SettingType.BOOL, "false", all3, Strictness.FALSE),
        SettingDef(Keys.AGENT_PATCH_APPROVAL, "Patches require approval",
            "A patch or bugfix from an agent needs human approval before it is applied.",
            SettingType.BOOL, "true", all3, Strictness.TRUE),
        SettingDef(Keys.TIMELINE_AUTO, "Automatic timeline",
            "The internal agent adds alert updates and findings to the incident timeline.",
            SettingType.BOOL, "true", all3, Strictness.NONE),
        SettingDef(Keys.MCP_WRITE, "MCP write access",
            "External agents can change incidents and alerts through the MCP server. Read access is always on.",
            SettingType.BOOL, "false", all3, Strictness.FALSE),
        SettingDef(Keys.AGENT_EXCHANGE_ADMIN, "Admin rights through token exchange",
            "Administrators can allow agents to keep their admin scope when the agent exchanges their token (RFC 8693). Each administrator must also turn it on for themselves.",
            SettingType.BOOL, "false", setOf(Scope.ORG), Strictness.FALSE),
        SettingDef(Keys.INCIDENT_PREFIX, "Incident key prefix",
            "The prefix of incident identifiers, for example INC in INC-42.",
            SettingType.STRING, "INC", setOf(Scope.ORG), Strictness.NONE),
    )

    private val byKey = defs.associateBy { it.key }
    fun def(key: String) = byKey[key] ?: throw Invalid("Unknown setting '$key'")
}

const val ORG_ID = "org"

@Serializable
data class SettingValue(
    val scope: Scope,
    val scopeId: String,
    val key: String,
    val value: String,
    val enforcement: Enforcement = Enforcement.NONE,
    val exception: Boolean = false,
    val updatedBy: Actor,
    val updatedAt: Ts,
) {
    val id: String get() = "${scope.name}:$scopeId:$key"
}

@Serializable
enum class ChangeRequestStatus { PENDING, APPROVED, REJECTED }

@Serializable
data class ChangeRequest(
    val id: String,
    val scope: Scope,
    val scopeId: String,
    val key: String,
    val proposedValue: String,
    val reason: String,
    val status: ChangeRequestStatus = ChangeRequestStatus.PENDING,
    val requestedBy: Actor,
    val createdAt: Ts,
    val decidedBy: Actor? = null,
    val decidedAt: Ts? = null,
    val decisionNote: String = "",
)

interface SettingRepository {
    suspend fun all(): List<SettingValue>
    suspend fun save(value: SettingValue)
    suspend fun delete(id: String)
}

interface ChangeRequestRepository {
    suspend fun get(id: String): ChangeRequest?
    suspend fun list(status: ChangeRequestStatus?): List<ChangeRequest>
    suspend fun save(request: ChangeRequest)
}

@Serializable
data class EffectiveSetting(
    val key: String,
    val value: String,
    val source: Scope?,
    val enforcedBy: Scope? = null,
    val enforcement: Enforcement = Enforcement.NONE,
    val enforcedValue: String? = null,
)

class SettingLocked(message: String) : DomainException("locked", message)

/** Read model that other bounded contexts use. */
/** Port for contexts that react when an organization-level setting changes on this instance. */
interface OrgSettingListener {
    suspend fun orgSettingChanged(key: String)
}

interface PolicyReader {
    suspend fun effective(teamIds: List<String>): Map<String, String>
    suspend fun bool(key: String, teamIds: List<String>) = effective(teamIds)[key] == "true"
    suspend fun int(key: String, teamIds: List<String>) = effective(teamIds)[key]?.toIntOrNull() ?: SettingCatalog.def(key).default.toInt()
    suspend fun severity(key: String, teamIds: List<String>): Severity? =
        effective(teamIds)[key]?.takeIf { it != SEVERITY_NONE }?.let { Severity.valueOf(it) }
    suspend fun string(key: String) = effective(emptyList())[key] ?: SettingCatalog.def(key).default
}

class PolicyService(
    private val settings: SettingRepository,
    private val requests: ChangeRequestRepository,
    private val directory: DirectoryService,
    private val events: EventPublisher,
    private val clock: Clock,
) : PolicyReader {
    @Volatile private var cache: Map<String, SettingValue>? = null
    @Volatile var listener: OrgSettingListener = object : OrgSettingListener { override suspend fun orgSettingChanged(key: String) = Unit }

    private suspend fun index(): Map<String, SettingValue> = cache ?: settings.all().associateBy { it.id }.also { cache = it }

    fun invalidate() { cache = null }

    fun schema() = SettingCatalog.defs

    suspend fun raw(scope: Scope, scopeId: String): List<SettingValue> =
        index().values.filter { it.scope == scope && it.scopeId == scopeId }

    private suspend fun chain(scope: Scope, scopeId: String): List<Pair<Scope, String>> = when (scope) {
        Scope.ORG -> listOf(Scope.ORG to ORG_ID)
        Scope.PROJECT -> listOf(Scope.ORG to ORG_ID, Scope.PROJECT to scopeId)
        Scope.TEAM -> {
            val team = directory.teamOrNull(scopeId)
            listOfNotNull(Scope.ORG to ORG_ID, team?.projectId?.let { Scope.PROJECT to it }, Scope.TEAM to scopeId)
        }
    }

    suspend fun effectiveAt(scope: Scope, scopeId: String): List<EffectiveSetting> {
        val idx = index()
        val levels = chain(scope, scopeId)
        return SettingCatalog.defs.map { def -> resolve(def, levels, idx) }
    }

    private fun resolve(def: SettingDef, levels: List<Pair<Scope, String>>, idx: Map<String, SettingValue>): EffectiveSetting {
        var value = def.default
        var source: Scope? = null
        var constraint: SettingValue? = null
        for ((s, id) in levels) {
            val v = idx["${s.name}:$id:${def.key}"] ?: continue
            val accepted = when (constraint?.enforcement) {
                null, Enforcement.NONE -> true
                Enforcement.LOCKED -> v.exception
                Enforcement.MINIMUM -> v.exception || atLeastAsStrict(def, v.value, constraint.value)
            }
            if (!accepted) continue
            value = v.value
            source = s
            if (v.enforcement != Enforcement.NONE) constraint = v
        }
        return EffectiveSetting(def.key, value, source, constraint?.scope, constraint?.enforcement ?: Enforcement.NONE, constraint?.value)
    }

    override suspend fun effective(teamIds: List<String>): Map<String, String> {
        if (teamIds.isEmpty()) return effectiveAt(Scope.ORG, ORG_ID).associate { it.key to it.value }
        val perTeam = teamIds.map { t -> effectiveAt(Scope.TEAM, t).associate { it.key to it.value } }
        return SettingCatalog.defs.associate { def ->
            def.key to perTeam.map { it.getValue(def.key) }.reduce { a, b -> stricter(def, a, b) }
        }
    }

    suspend fun set(by: Principal, scope: Scope, scopeId: String, key: String, value: String, enforcement: Enforcement): SettingValue {
        val def = SettingCatalog.def(key)
        val id = normalizeScopeId(scope, scopeId)
        if (scope !in def.scopes) throw Invalid("'$key' cannot be set at ${scope.name.lowercase()} level")
        requireManage(by, scope, id)
        if (enforcement != Enforcement.NONE && !Access.isAdmin(by)) throw Forbidden("Only administrators can enforce settings")
        val normalized = validate(def, value)
        val levels = chain(scope, id).dropLast(1)
        val parent = resolve(def, levels, index())
        if (!Access.isAdmin(by)) checkConstraint(def, parent, normalized)
        val sv = SettingValue(scope, id, key, normalized, enforcement, exception = false, updatedBy = by.actor, updatedAt = clock.now())
        settings.save(sv)
        invalidate()
        events.publish(DomainEvent("setting.changed", sv.id, clock.now(), data = mapOf("key" to key)))
        if (scope == Scope.ORG) listener.orgSettingChanged(key)
        return sv
    }

    suspend fun clear(by: Principal, scope: Scope, scopeId: String, key: String) {
        SettingCatalog.def(key)
        val id = normalizeScopeId(scope, scopeId)
        requireManage(by, scope, id)
        val existing = index()["${scope.name}:$id:$key"] ?: return
        if (existing.enforcement != Enforcement.NONE && !Access.isAdmin(by)) throw Forbidden("Only administrators can remove an enforced setting")
        settings.delete(existing.id)
        invalidate()
        events.publish(DomainEvent("setting.changed", existing.id, clock.now(), data = mapOf("key" to key)))
        if (scope == Scope.ORG) listener.orgSettingChanged(key)
    }

    suspend fun requestChange(by: Principal, scope: Scope, scopeId: String, key: String, proposed: String, reason: String): ChangeRequest {
        Access.requireWrite(by)
        val def = SettingCatalog.def(key)
        val id = normalizeScopeId(scope, scopeId)
        if (scope !in def.scopes) throw Invalid("'$key' cannot be set at ${scope.name.lowercase()} level")
        val cr = ChangeRequest(Ids.new(), scope, id, key, validate(def, proposed), reason.trim(), requestedBy = by.actor, createdAt = clock.now())
        requests.save(cr)
        events.publish(DomainEvent("change_request.created", cr.id, clock.now()))
        return cr
    }

    suspend fun changeRequests(status: ChangeRequestStatus?) = requests.list(status).sortedByDescending { it.createdAt }

    suspend fun decide(by: Principal, id: String, approve: Boolean, note: String): ChangeRequest {
        Access.requireAdmin(by)
        val cr = requests.get(id) ?: throw NotFound("Change request")
        if (cr.status != ChangeRequestStatus.PENDING) throw Invalid("This request is already decided")
        if (approve) {
            settings.save(SettingValue(cr.scope, cr.scopeId, cr.key, cr.proposedValue, Enforcement.NONE, exception = true, updatedBy = by.actor, updatedAt = clock.now()))
            invalidate()
            if (cr.scope == Scope.ORG) listener.orgSettingChanged(cr.key)
        }
        val decided = cr.copy(
            status = if (approve) ChangeRequestStatus.APPROVED else ChangeRequestStatus.REJECTED,
            decidedBy = by.actor, decidedAt = clock.now(), decisionNote = note.trim(),
        )
        requests.save(decided)
        events.publish(DomainEvent("change_request.decided", cr.id, clock.now()))
        return decided
    }

    private suspend fun normalizeScopeId(scope: Scope, scopeId: String): String = when (scope) {
        Scope.ORG -> ORG_ID
        Scope.PROJECT -> directory.project(scopeId).id
        Scope.TEAM -> directory.team(scopeId).id
    }

    private suspend fun requireManage(by: Principal, scope: Scope, scopeId: String) {
        val ok = when (scope) {
            Scope.ORG -> Access.isAdmin(by)
            Scope.PROJECT -> Access.isProjectAdmin(by, scopeId)
            Scope.TEAM -> Access.isTeamAdmin(by, scopeId, directory.team(scopeId).projectId)
        }
        if (!ok) throw Forbidden("You cannot change settings at this level")
    }

    private fun checkConstraint(def: SettingDef, parent: EffectiveSetting, value: String) {
        when (parent.enforcement) {
            Enforcement.NONE -> Unit
            Enforcement.LOCKED -> throw SettingLocked("An administrator locked '${def.label}' at ${parent.enforcedBy?.name?.lowercase()} level. Request a change instead.")
            Enforcement.MINIMUM -> if (!atLeastAsStrict(def, value, parent.enforcedValue!!)) {
                throw SettingLocked("'${def.label}' must be at least as strict as ${parent.enforcedValue}. Request a change instead.")
            }
        }
    }

    companion object {
        fun validate(def: SettingDef, value: String): String {
            val v = value.trim()
            return when (def.type) {
                SettingType.BOOL -> when (v.lowercase()) {
                    "true", "1", "yes", "on" -> "true"
                    "false", "0", "no", "off" -> "false"
                    else -> throw Invalid("'${def.label}' must be true or false")
                }
                SettingType.INT -> {
                    val n = v.toIntOrNull() ?: throw Invalid("'${def.label}' must be a number")
                    if (def.min != null && n < def.min || def.max != null && n > def.max) {
                        throw Invalid("'${def.label}' must be between ${def.min} and ${def.max}")
                    }
                    n.toString()
                }
                SettingType.SEVERITY -> v.uppercase().also {
                    if (it != SEVERITY_NONE && Severity.entries.none { s -> s.name == it }) throw Invalid("'${def.label}' must be SEV1..SEV5 or NONE")
                }
                SettingType.STRING -> v.also {
                    if (it.isEmpty() || it.length > 16 || !it.all { c -> c.isLetterOrDigit() }) throw Invalid("'${def.label}' must be 1-16 letters or digits")
                }
            }
        }

        private fun severityCoverage(v: String) = if (v == SEVERITY_NONE) 0 else Severity.valueOf(v).rank

        /** True when [value] is at least as strict as [floor]. */
        fun atLeastAsStrict(def: SettingDef, value: String, floor: String): Boolean = when (def.strictness) {
            Strictness.NONE -> value == floor
            Strictness.TRUE -> value == "true" || floor == "false"
            Strictness.FALSE -> value == "false" || floor == "true"
            Strictness.HIGHER -> if (def.type == SettingType.SEVERITY) severityCoverage(value) >= severityCoverage(floor) else value.toInt() >= floor.toInt()
            Strictness.LOWER -> value.toInt() <= floor.toInt()
        }

        fun stricter(def: SettingDef, a: String, b: String): String =
            if (def.strictness == Strictness.NONE) a else if (atLeastAsStrict(def, a, b)) a else b
    }
}
