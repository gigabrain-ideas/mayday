package mayday.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mayday.agents.AgentService
import mayday.agents.ToolCatalog
import mayday.agents.ToolOutcome
import mayday.agents.ToolSpec
import mayday.alerting.AlertFilter
import mayday.alerting.AlertService
import mayday.alerting.AlertSignal
import mayday.alerting.AlertStatus
import mayday.directory.DirectoryService
import mayday.identity.Access
import mayday.identity.IdentityService
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.incidents.ActionItem
import mayday.incidents.IncidentFilter
import mayday.incidents.IncidentPatch
import mayday.incidents.IncidentService
import mayday.incidents.IncidentStatus
import mayday.incidents.NewIncident
import mayday.incidents.Postmortem
import mayday.incidents.PostmortemStatus
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.PolicyService
import mayday.policy.Scope
import mayday.shared.DomainException
import mayday.shared.Forbidden
import mayday.shared.Invalid
import mayday.shared.JSON
import mayday.shared.Severity
import java.time.Instant

/** Schema helpers for tool definitions. */
object S {
    fun str(desc: String, enum: List<String>? = null) = buildJsonObject {
        put("type", "string"); put("description", desc)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
    }
    fun int(desc: String) = buildJsonObject { put("type", "integer"); put("description", desc) }
    fun bool(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
    fun arr(desc: String, items: JsonObject) = buildJsonObject { put("type", "array"); put("description", desc); put("items", items) }
    fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.strList(k: String): List<String>? = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }
private fun JsonObject.req(k: String) = str(k)?.takeIf { it.isNotBlank() } ?: throw Invalid("'$k' is required")
private fun JsonObject.instant(k: String) = str(k)?.let { runCatching { Instant.parse(it) }.getOrElse { throw Invalid("'$k' must be an ISO-8601 time") } }
private inline fun <reified E : Enum<E>> JsonObject.enum(k: String): E? = str(k)?.let { v ->
    enumValues<E>().firstOrNull { it.name.equals(v, true) } ?: throw Invalid("'$k' has an unknown value '$v'")
}

/**
 * The application's use cases exposed as tools. The MCP server exposes them to external agents and the built-in agent
 * calls them directly. Every call runs with the caller's principal, so RBAC is the same as in the web interface.
 */
class MaydayTools(
    private val incidents: IncidentService,
    private val alerts: AlertService,
    private val directory: DirectoryService,
    private val policy: PolicyService,
    private val identity: () -> IdentityService,
    private val agents: () -> AgentService,
) : ToolCatalog {
    private class Tool(val spec: ToolSpec, val handler: suspend (Principal, JsonObject, Gate) -> JsonElement)

    /** Checks the MCP write switch for external callers. */
    private inner class Gate(val internal: Boolean) {
        suspend fun write(teamIds: List<String>) {
            if (!internal && !policy.bool(Keys.MCP_WRITE, teamIds)) {
                throw Forbidden("MCP write access is turned off for these teams. An administrator can turn on '${Keys.MCP_WRITE}'.")
            }
        }
        suspend fun incident(p: Principal, ref: String) = incidents.get(p, ref).also { write(it.teamIds) }
        suspend fun alert(p: Principal, id: String) = alerts.get(p, id).also { write(listOf(it.teamId)) }
    }

    private val sevs = Severity.entries.map { it.name }
    private val list = listOf(
        Tool(ToolSpec("list_incidents", "List incidents, newest first. Filter by status, severity, team or text.",
            S.obj(emptyList(),
                "status" to S.str("Incident status", IncidentStatus.entries.map { it.name }),
                "severity" to S.str("Severity", sevs),
                "team" to S.str("Team id or slug"),
                "query" to S.str("Text to search in key and title"),
                "open_only" to S.bool("Only open incidents"),
                "limit" to S.int("Maximum number of results, 1-100"),
            ))) { p, a, _ ->
            val statuses = a.enum<IncidentStatus>("status")?.let { setOf(it) }
                ?: if (a.bool("open_only") == true) IncidentStatus.entries.filter { it.open }.toSet() else emptySet()
            val f = IncidentFilter(statuses, a.enum<Severity>("severity")?.let { setOf(it) } ?: emptySet(),
                a.str("team")?.let { setOf(directory.team(it).id) } ?: emptySet(), a.str("query"))
            JSON.encodeToJsonElement(incidents.list(p, f, null, a.int("limit") ?: 25).items.map { summary(it) })
        },
        Tool(ToolSpec("get_incident", "Get one incident with its timeline, postmortem and runbook.",
            S.obj(listOf("ref"), "ref" to S.str("Incident key (INC-42), number or id")))) { p, a, _ ->
            val inc = incidents.get(p, a.req("ref"))
            buildJsonObject {
                put("incident", JSON.encodeToJsonElement(inc))
                put("timeline", JSON.encodeToJsonElement(incidents.timeline(p, inc.id).takeLast(300)))
            }
        },
        Tool(ToolSpec("list_alerts", "List alerts, newest first.",
            S.obj(emptyList(),
                "status" to S.str("Alert status", AlertStatus.entries.map { it.name }),
                "severity" to S.str("Severity", sevs),
                "team" to S.str("Team id or slug"),
                "query" to S.str("Text to search in the title"),
                "limit" to S.int("Maximum number of results, 1-100"),
            ))) { p, a, _ ->
            val f = AlertFilter(a.enum<AlertStatus>("status")?.let { setOf(it) } ?: emptySet(),
                a.enum<Severity>("severity")?.let { setOf(it) } ?: emptySet(),
                a.str("team")?.let { setOf(directory.team(it).id) } ?: emptySet(), a.str("query"))
            JSON.encodeToJsonElement(alerts.list(p, f, null, a.int("limit") ?: 25).items)
        },
        Tool(ToolSpec("get_alert", "Get one alert with its updates.", S.obj(listOf("id"), "id" to S.str("Alert id")))) { p, a, _ ->
            JSON.encodeToJsonElement(alerts.get(p, a.req("id")))
        },
        Tool(ToolSpec("list_teams", "List teams and projects.", S.obj())) { p, _, _ ->
            Access.requireRead(p)
            buildJsonObject {
                put("teams", JSON.encodeToJsonElement(directory.teams()))
                put("projects", JSON.encodeToJsonElement(directory.projects()))
            }
        },
        Tool(ToolSpec("get_settings", "Get the effective settings of a team, or of the organization when no team is given.",
            S.obj(emptyList(), "team" to S.str("Team id or slug")))) { p, a, _ ->
            Access.requireRead(p)
            val r = a.str("team")?.let { policy.effectiveAt(Scope.TEAM, directory.team(it).id) } ?: policy.effectiveAt(Scope.ORG, "org")
            JSON.encodeToJsonElement(r)
        },
        Tool(ToolSpec("create_incident", "Declare a new incident.",
            S.obj(listOf("title", "severity", "teams"),
                "title" to S.str("Short title"),
                "description" to S.str("What is happening"),
                "severity" to S.str("Severity", sevs),
                "teams" to S.arr("Responder team ids or slugs", S.str("Team")),
                "impact_started_at" to S.str("ISO-8601 time when impact started"),
            ), write = true)) { p, a, g ->
            val teams = a.strList("teams")?.map { directory.team(it).id } ?: throw Invalid("'teams' is required")
            g.write(teams)
            val inc = incidents.declare(p, NewIncident(a.req("title"), a.str("description").orEmpty(),
                a.enum<Severity>("severity") ?: throw Invalid("'severity' is required"), teams, impactStartedAt = a.instant("impact_started_at")))
            summary(inc)
        },
        Tool(ToolSpec("update_incident", "Change status, severity, title, description, teams or impact times of an incident.",
            S.obj(listOf("ref"),
                "ref" to S.str("Incident key, number or id"),
                "status" to S.str("New status", IncidentStatus.entries.map { it.name }),
                "severity" to S.str("New severity", sevs),
                "title" to S.str("New title"),
                "description" to S.str("New description"),
                "teams" to S.arr("Responder team ids or slugs", S.str("Team")),
                "impact_started_at" to S.str("ISO-8601"),
                "impact_ended_at" to S.str("ISO-8601"),
                "discovered_at" to S.str("ISO-8601"),
            ), write = true)) { p, a, g ->
            val inc = g.incident(p, a.req("ref"))
            val updated = incidents.update(p, inc.id, IncidentPatch(
                title = a.str("title"), description = a.str("description"), severity = a.enum<Severity>("severity"),
                status = a.enum<IncidentStatus>("status"), teamIds = a.strList("teams"),
                impactStartedAt = a.instant("impact_started_at"), impactEndedAt = a.instant("impact_ended_at"), discoveredAt = a.instant("discovered_at"),
            ))
            summary(updated)
        },
        Tool(ToolSpec("add_timeline_entry", "Add an entry to the timeline of an incident.",
            S.obj(listOf("ref", "text"),
                "ref" to S.str("Incident key, number or id"),
                "text" to S.str("What happened, in one or two sentences"),
                "at" to S.str("ISO-8601 time of the event. Default is now."),
            ), write = true)) { p, a, g ->
            val inc = g.incident(p, a.req("ref"))
            JSON.encodeToJsonElement(incidents.addEntry(p, inc.id, a.req("text"), at = a.instant("at")))
        },
        Tool(ToolSpec("set_postmortem", "Create or replace the postmortem of an incident.",
            S.obj(listOf("ref", "summary"),
                "ref" to S.str("Incident key, number or id"),
                "summary" to S.str("Summary"),
                "impact" to S.str("Customer and business impact"),
                "rootCause" to S.str("Root cause and contributing factors"),
                "detection" to S.str("How the incident was detected"),
                "resolution" to S.str("How the incident was resolved"),
                "lessons" to S.str("What went well, what went wrong, where we got lucky"),
                "actionItems" to S.arr("Follow-up actions", S.obj(listOf("text"), "text" to S.str("Action"), "owner" to S.str("Owner"))),
                "status" to S.str("Postmortem status", PostmortemStatus.entries.map { it.name }),
            ), write = true)) { p, a, g ->
            val inc = g.incident(p, a.req("ref"))
            val items = (a["actionItems"] as? JsonArray)?.map { it.jsonObject }?.map { ActionItem(it.req("text"), it.str("owner").orEmpty()) } ?: emptyList()
            val pm = Postmortem(
                status = a.enum<PostmortemStatus>("status") ?: PostmortemStatus.DRAFT,
                summary = a.req("summary"), impact = a.str("impact").orEmpty(), rootCause = a.str("rootCause").orEmpty(),
                detection = a.str("detection").orEmpty(), resolution = a.str("resolution").orEmpty(), lessons = a.str("lessons").orEmpty(),
                actionItems = items,
            )
            summary(incidents.savePostmortem(p, inc.id, pm))
        },
        Tool(ToolSpec("save_runbook", "Save a runbook (standard operating procedure) for an incident. Use ASD-STE100.",
            S.obj(listOf("ref", "title", "body"),
                "ref" to S.str("Incident key, number or id"),
                "title" to S.str("Runbook title"),
                "body" to S.str("Runbook in Markdown"),
            ), write = true)) { p, a, g ->
            val inc = g.incident(p, a.req("ref"))
            summary(incidents.saveRunbook(p, inc.id, a.str("title").orEmpty(), a.req("body")))
        },
        Tool(ToolSpec("save_resolution", "Write how the issue was solved.",
            S.obj(listOf("ref", "notes"), "ref" to S.str("Incident key, number or id"), "notes" to S.str("Resolution notes")), write = true)) { p, a, g ->
            val inc = g.incident(p, a.req("ref"))
            summary(incidents.saveResolution(p, inc.id, a.req("notes")))
        },
        Tool(ToolSpec("send_alert", "Send an alert signal to Mayday.",
            S.obj(listOf("title", "team"),
                "title" to S.str("Alert title"), "team" to S.str("Team id or slug"), "severity" to S.str("Severity", sevs),
                "description" to S.str("Details"), "fingerprint" to S.str("Deduplication key"),
            ), write = true)) { p, a, g ->
            val team = directory.team(a.req("team"))
            g.write(listOf(team.id))
            JSON.encodeToJsonElement(alerts.ingest(p, AlertSignal(a.req("title"), a.enum<Severity>("severity") ?: Severity.SEV3, team.id,
                source = "mcp:${p.actor.name}", fingerprint = a.str("fingerprint"), description = a.str("description").orEmpty())))
        },
        Tool(ToolSpec("acknowledge_alert", "Acknowledge an alert.", S.obj(listOf("id"), "id" to S.str("Alert id")), write = true)) { p, a, g ->
            JSON.encodeToJsonElement(alerts.acknowledge(p, g.alert(p, a.req("id")).id))
        },
        Tool(ToolSpec("resolve_alert", "Resolve an alert.", S.obj(listOf("id"), "id" to S.str("Alert id"), "note" to S.str("Why it is resolved")), write = true)) { p, a, g ->
            JSON.encodeToJsonElement(alerts.resolve(p, g.alert(p, a.req("id")).id, a.str("note").orEmpty()))
        },
        Tool(ToolSpec("add_alert_update", "Add an update to an alert.", S.obj(listOf("id", "text"), "id" to S.str("Alert id"), "text" to S.str("Update")), write = true)) { p, a, g ->
            JSON.encodeToJsonElement(alerts.addUpdate(p, g.alert(p, a.req("id")).id, a.req("text")))
        },
        Tool(ToolSpec("claim_agent_runs", "For pull agents: claim queued runs for the calling agent identity.",
            S.obj(emptyList(), "limit" to S.int("Maximum runs, 1-50")))) { p, a, _ ->
            JSON.encodeToJsonElement(agents().claim(p, a.int("limit") ?: 5))
        },
        Tool(ToolSpec("report_agent_run", "For agents: report progress or the result of a run.",
            S.obj(listOf("run_id", "state"),
                "run_id" to S.str("Run id"),
                "state" to S.str("Progress or final state", listOf("progress", "succeeded", "failed")),
                "text" to S.str("Progress note, final report or error"),
            ))) { p, a, _ ->
            val id = a.req("run_id")
            val text = a.str("text").orEmpty()
            val r = when (a.req("state")) {
                "progress" -> agents().progress(p, id, text)
                "succeeded" -> agents().complete(p, id, true, text, "")
                "failed" -> agents().complete(p, id, false, "", text)
                else -> throw Invalid("Unknown state")
            }
            JSON.encodeToJsonElement(r)
        },
        Tool(ToolSpec("set_setting", "Administrators: set a setting at organization, project or team level.",
            S.obj(listOf("scope", "key", "value"),
                "scope" to S.str("Level", Scope.entries.map { it.name }),
                "scope_id" to S.str("Project or team id or slug. Not used for ORG."),
                "key" to S.str("Setting key"),
                "value" to S.str("New value"),
                "enforcement" to S.str("Enforcement for lower levels", Enforcement.entries.map { it.name }),
            ), write = true, admin = true)) { p, a, g ->
            g.write(emptyList())
            val scope = a.enum<Scope>("scope") ?: throw Invalid("'scope' is required")
            JSON.encodeToJsonElement(policy.set(p, scope, a.str("scope_id") ?: "org", a.req("key"), a.req("value"), a.enum<Enforcement>("enforcement") ?: Enforcement.NONE))
        },
        Tool(ToolSpec("list_users", "Administrators: list users and their roles.",
            S.obj(emptyList(), "query" to S.str("Name or email"), "limit" to S.int("1-200")), admin = true)) { p, a, _ ->
            Access.requireAdmin(p)
            JSON.encodeToJsonElement(identity().list(a.str("query"), null, a.int("limit") ?: 50).items)
        },
        Tool(ToolSpec("set_user_role", "Administrators: change the organization role of a user.",
            S.obj(listOf("user_id", "role"), "user_id" to S.str("User id"), "role" to S.str("Role", OrgRole.entries.map { it.name })),
            write = true, admin = true)) { p, a, g ->
            g.write(emptyList())
            JSON.encodeToJsonElement(identity().updateUser(p, a.req("user_id"), role = a.enum<OrgRole>("role")))
        },
    )

    private val byName = list.associateBy { it.spec.name }

    private fun summary(i: mayday.incidents.Incident) = buildJsonObject {
        put("key", i.key); put("id", i.id); put("title", i.title); put("severity", i.severity.name); put("status", i.status.name)
        put("teamIds", JSON.encodeToJsonElement(i.teamIds)); put("reportedAt", i.reportedAt.toString())
        i.resolvedAt?.let { put("resolvedAt", it.toString()) }
    }

    override suspend fun tools(principal: Principal, internal: Boolean): List<ToolSpec> = list.map { it.spec }.filter { spec ->
        when {
            spec.admin -> Access.isAdmin(principal)
            spec.name in setOf("claim_agent_runs", "report_agent_run") -> principal.isAgent && !internal
            spec.write -> principal.role.rank >= OrgRole.MEMBER.rank && principal.has(mayday.identity.TokenScope.WRITE)
            else -> true
        }
    }

    override suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome {
        val tool = byName[name] ?: return ToolOutcome("Unknown tool '$name'", true)
        return try {
            val r = tool.handler(principal, args, Gate(internal))
            ToolOutcome(JSON.encodeToString(JsonElement.serializer(), r))
        } catch (e: DomainException) {
            ToolOutcome("${e.code}: ${e.message}", true)
        }
    }
}

/** Combines tool catalogs; names of later catalogs get a prefix when they collide. */
class CompositeCatalog(private val parts: List<ToolCatalog>) : ToolCatalog {
    override suspend fun tools(principal: Principal, internal: Boolean) = parts.flatMap { it.tools(principal, internal) }.distinctBy { it.name }

    override suspend fun call(principal: Principal, name: String, args: JsonObject, internal: Boolean): ToolOutcome {
        for (p in parts) if (p.tools(principal, internal).any { it.name == name }) return p.call(principal, name, args, internal)
        return ToolOutcome("Unknown tool '$name'", true)
    }
}
