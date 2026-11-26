package mayday.adapters.memory

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mayday.agents.AgentConfig
import mayday.agents.AgentRepository
import mayday.agents.AgentRun
import mayday.agents.ConfigRepository
import mayday.agents.LlmConfig
import mayday.agents.McpServerConfig
import mayday.agents.McpServerRepository
import mayday.agents.RunRepository
import mayday.agents.RunStatus
import mayday.alerting.Alert
import mayday.alerting.AlertFilter
import mayday.alerting.AlertRepository
import mayday.directory.Project
import mayday.directory.ProjectRepository
import mayday.directory.Team
import mayday.directory.TeamRepository
import mayday.identity.ApiToken
import mayday.identity.OwnerKind
import mayday.identity.TokenRepository
import mayday.identity.User
import mayday.identity.UserRepository
import mayday.incidents.Incident
import mayday.incidents.IncidentFilter
import mayday.incidents.IncidentRepository
import mayday.incidents.TimelineEntry
import mayday.incidents.TimelineRepository
import mayday.policy.ChangeRequest
import mayday.policy.ChangeRequestRepository
import mayday.policy.ChangeRequestStatus
import mayday.policy.SettingRepository
import mayday.policy.SettingValue
import mayday.oncall.OverrideRepository
import mayday.oncall.Schedule
import mayday.oncall.ScheduleOverride
import mayday.oncall.ScheduleRepository
import mayday.escalation.Escalation
import mayday.escalation.EscalationPolicy
import mayday.escalation.EscalationPolicyRepository
import mayday.escalation.EscalationRepository
import mayday.escalation.EscalationStatus
import mayday.provisioning.DirectoryGroup
import mayday.runbooks.Runbook
import mayday.runbooks.RunbookRepository
import mayday.provisioning.GroupRepository
import mayday.shared.Conflict
import mayday.shared.JSON
import mayday.shared.Page
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-node store: all data lives in memory, every change is appended to a JSON-lines journal.
 * The journal is compacted at start. Without a directory the store is ephemeral (tests, demos).
 */
class Journal(private val file: Path?) {
    private var writer: BufferedWriter? = null

    fun replay(apply: (String, String, JsonElement?) -> Unit) {
        if (file == null || !Files.exists(file)) return
        Files.newBufferedReader(file).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val o = runCatching { JSON.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                apply(o["c"]!!.jsonPrimitive.content, o["id"]!!.jsonPrimitive.content, o["v"])
            }
        }
    }

    fun open(snapshot: Sequence<String>) {
        if (file == null) return
        Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { w -> snapshot.forEach { w.write(it); w.newLine() } }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        writer = Files.newBufferedWriter(file, StandardOpenOption.APPEND)
    }

    @Synchronized
    fun write(collection: String, id: String, value: JsonElement?) {
        val w = writer ?: return
        w.write(line(collection, id, value))
        w.newLine()
        w.flush()
    }

    fun close() { writer?.close() }

    companion object {
        fun line(collection: String, id: String, value: JsonElement?) = JSON.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("c", collection); put("id", id); if (value != null) put("v", value)
        })
    }
}

class Coll<T>(val name: String, private val ser: KSerializer<T>, private val journal: Journal) {
    val map = ConcurrentHashMap<String, T>()

    fun put(id: String, v: T) {
        map[id] = v
        journal.write(name, id, JSON.encodeToJsonElement(ser, v))
    }

    fun remove(id: String) {
        if (map.remove(id) != null) journal.write(name, id, null)
    }

    fun load(id: String, v: JsonElement?) {
        if (v == null) map.remove(id) else map[id] = JSON.decodeFromJsonElement(ser, v)
    }

    fun dump(): Sequence<String> = map.entries.asSequence().map { (id, v) -> Journal.line(name, id, JSON.encodeToJsonElement(ser, v)) }
}

class MemoryStore(dataDir: Path?) : AutoCloseable {
    private val journal = Journal(dataDir?.resolve("mayday.journal"))

    private val projectsC = Coll("projects", Project.serializer(), journal)
    private val teamsC = Coll("teams", Team.serializer(), journal)
    private val usersC = Coll("users", User.serializer(), journal)
    private val tokensC = Coll("tokens", ApiToken.serializer(), journal)
    private val settingsC = Coll("settings", SettingValue.serializer(), journal)
    private val requestsC = Coll("change_requests", ChangeRequest.serializer(), journal)
    private val incidentsC = Coll("incidents", Incident.serializer(), journal)
    private val timelineC = Coll("timeline", TimelineEntry.serializer(), journal)
    private val alertsC = Coll("alerts", Alert.serializer(), journal)
    private val agentsC = Coll("agents", AgentConfig.serializer(), journal)
    private val runsC = Coll("runs", AgentRun.serializer(), journal)
    private val mcpC = Coll("mcp_servers", McpServerConfig.serializer(), journal)
    private val configC = Coll("config", JsonElement.serializer(), journal)
    private val schedulesC = Coll("schedules", Schedule.serializer(), journal)
    private val overridesC = Coll("schedule_overrides", ScheduleOverride.serializer(), journal)
    private val groupsC = Coll("directory_groups", DirectoryGroup.serializer(), journal)
    private val escalationPoliciesC = Coll("escalation_policies", EscalationPolicy.serializer(), journal)
    private val escalationsC = Coll("escalations", Escalation.serializer(), journal)
    private val runbooksC = Coll("runbooks", Runbook.serializer(), journal)
    private val all = listOf(projectsC, teamsC, usersC, tokensC, settingsC, requestsC, incidentsC, timelineC, alertsC, agentsC, runsC, mcpC, configC,
        schedulesC, overridesC, groupsC, escalationPoliciesC, escalationsC, runbooksC)

    private val incidentOrder = ConcurrentSkipListMap<Long, String>(reverseOrder())
    private val counter = AtomicLong(0)
    private val timelineByIncident = ConcurrentHashMap<String, CopyOnWriteArrayList<TimelineEntry>>()
    private val alertOrder = ConcurrentSkipListMap<String, String>(reverseOrder())
    private val alertByFingerprint = ConcurrentHashMap<String, String>()
    private val usersByEmail = ConcurrentHashMap<String, String>()
    private val usersByExternal = ConcurrentHashMap<String, String>()
    private val tokensByHash = ConcurrentHashMap<String, String>()

    init {
        val byName = all.associateBy { it.name }
        journal.replay { c, id, v -> if (c == "counter") counter.set(v!!.jsonPrimitive.content.toLong()) else byName[c]?.load(id, v) }
        incidentsC.map.values.forEach { incidentOrder[it.number] = it.id; if (it.number > counter.get()) counter.set(it.number) }
        timelineC.map.values.sortedBy { it.at }.forEach { timelineByIncident.computeIfAbsent(it.incidentId) { CopyOnWriteArrayList() } += it }
        alertsC.map.values.sortedBy { it.firstSeenAt }.forEach { indexAlert(it) }
        usersC.map.values.forEach { indexUser(it) }
        tokensC.map.values.forEach { tokensByHash[it.hash] = it.id }
        journal.open(all.asSequence().flatMap { it.dump() } + sequenceOf(Journal.line("counter", "incident", JsonPrimitive(counter.get().toString()))))
    }

    override fun close() { journal.close() }

    private fun alertKey(a: Alert) = "${a.firstSeenAt}|${a.id}"
    private fun indexAlert(a: Alert) { alertOrder[alertKey(a)] = a.id; alertByFingerprint[a.fingerprint] = a.id }
    private fun indexUser(u: User) { usersByEmail[u.email] = u.id; u.externalId?.let { usersByExternal[it] = u.id } }

    private fun <T> page(items: Sequence<T>, limit: Int, cursorOf: (T) -> String): Page<T> {
        val list = items.take(limit + 1).toList()
        return if (list.size > limit) Page(list.take(limit), cursorOf(list[limit - 1])) else Page(list)
    }

    private fun inRange(t: Instant, from: Instant?, to: Instant?) = (from == null || !t.isBefore(from)) && (to == null || t.isBefore(to))

    val projects = object : ProjectRepository {
        override suspend fun get(id: String) = projectsC.map[id]
        override suspend fun bySlug(slug: String) = projectsC.map.values.firstOrNull { it.slug == slug }
        override suspend fun list() = projectsC.map.values.toList()
        override suspend fun save(project: Project) = projectsC.put(project.id, project)
        override suspend fun delete(id: String) = projectsC.remove(id)
    }

    val teams = object : TeamRepository {
        override suspend fun get(id: String) = teamsC.map[id]
        override suspend fun bySlug(slug: String) = teamsC.map.values.firstOrNull { it.slug == slug }
        override suspend fun list() = teamsC.map.values.toList()
        override suspend fun save(team: Team) = teamsC.put(team.id, team)
        override suspend fun delete(id: String) = teamsC.remove(id)
    }

    val users = object : UserRepository {
        override suspend fun get(id: String) = usersC.map[id]
        override suspend fun byEmail(email: String) = usersByEmail[email.lowercase()]?.let { usersC.map[it] }
        override suspend fun byExternalId(externalId: String) = usersByExternal[externalId]?.let { usersC.map[it] }
        override suspend fun count() = usersC.map.size.toLong()
        override suspend fun withAgentAdmin() = usersC.map.values.filter { it.allowAgentAdmin }
        override suspend fun byTeam(teamId: String) = usersC.map.values.filter { teamId in it.teams }
        override suspend fun withGroups(keys: Set<String>) = usersC.map.values.filter { u -> u.idpGroups.any { it in keys } || u.scimGroups.any { it in keys } }
        override suspend fun byScimExternalId(externalId: String) = usersC.map.values.firstOrNull { it.scimExternalId == externalId }
        override suspend fun list(query: String?, cursor: String?, limit: Int): Page<User> {
            val q = query?.lowercase()?.trim().orEmpty()
            val seq = usersC.map.values.asSequence().sortedBy { it.email }
                .filter { cursor == null || it.email > cursor }
                .filter { q.isEmpty() || it.email.contains(q) || it.name.lowercase().contains(q) }
            return page(seq, limit) { it.email }
        }
        override suspend fun save(user: User) = synchronized(this) {
            usersC.map[user.id]?.let { old -> if (old.email != user.email) usersByEmail.remove(old.email) }
            usersByEmail[user.email]?.let { if (it != user.id) throw Conflict("A user with this email exists") }
            usersC.put(user.id, user)
            indexUser(user)
        }
    }

    val tokens = object : TokenRepository {
        override suspend fun byHash(hash: String) = tokensByHash[hash]?.let { tokensC.map[it] }
        override suspend fun byOwner(kind: OwnerKind, ownerId: String) = tokensC.map.values.filter { it.ownerKind == kind && it.ownerId == ownerId }
        override suspend fun get(id: String) = tokensC.map[id]
        override suspend fun save(token: ApiToken) { tokensC.put(token.id, token); tokensByHash[token.hash] = token.id }
        override suspend fun delete(id: String) { tokensC.map[id]?.let { tokensByHash.remove(it.hash) }; tokensC.remove(id) }
    }

    val settings = object : SettingRepository {
        override suspend fun all() = settingsC.map.values.toList()
        override suspend fun save(value: SettingValue) = settingsC.put(value.id, value)
        override suspend fun delete(id: String) = settingsC.remove(id)
    }

    val changeRequests = object : ChangeRequestRepository {
        override suspend fun get(id: String) = requestsC.map[id]
        override suspend fun list(status: ChangeRequestStatus?) = requestsC.map.values.filter { status == null || it.status == status }
        override suspend fun save(request: ChangeRequest) = requestsC.put(request.id, request)
    }

    val incidents = object : IncidentRepository {
        override suspend fun nextNumber(): Long {
            val n = counter.incrementAndGet()
            journal.write("counter", "incident", JsonPrimitive(n.toString()))
            return n
        }
        override suspend fun get(id: String) = incidentsC.map[id]
        override suspend fun byNumber(number: Long) = incidentOrder[number]?.let { incidentsC.map[it] }

        override suspend fun save(incident: Incident, expectedVersion: Long?) = synchronized(this) {
            val cur = incidentsC.map[incident.id]
            if (expectedVersion == null && cur != null) throw Conflict("Incident exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The incident changed. Reload and try again.")
            incidentsC.put(incident.id, incident)
            incidentOrder[incident.number] = incident.id
        }

        override suspend fun list(filter: IncidentFilter, cursor: String?, limit: Int): Page<Incident> {
            val start = cursor?.toLongOrNull()
            val src = if (start != null) incidentOrder.tailMap(start, false) else incidentOrder
            val seq = src.values.asSequence().mapNotNull { incidentsC.map[it] }.filter { matches(it, filter) }
            return page(seq, limit) { it.number.toString() }
        }

        override suspend fun scan(filter: IncidentFilter, consumer: (Incident) -> Unit) =
            incidentOrder.values.asSequence().mapNotNull { incidentsC.map[it] }.filter { matches(it, filter) }.forEach(consumer)

        private fun matches(i: Incident, f: IncidentFilter): Boolean {
            if (f.statuses.isNotEmpty() && i.status !in f.statuses) return false
            if (f.severities.isNotEmpty() && i.severity !in f.severities) return false
            if (f.teamIds.isNotEmpty() && i.teamIds.none { it in f.teamIds }) return false
            if (!inRange(i.reportedAt, f.from, f.to)) return false
            val q = f.query?.trim()?.lowercase()
            if (!q.isNullOrEmpty() && !i.title.lowercase().contains(q) && !i.key.lowercase().contains(q)) return false
            return true
        }
    }

    val timeline = object : TimelineRepository {
        override suspend fun append(entry: TimelineEntry) {
            timelineC.put(entry.id, entry)
            val list = timelineByIncident.computeIfAbsent(entry.incidentId) { CopyOnWriteArrayList() }
            synchronized(list) {
                val idx = list.indexOfLast { !it.at.isAfter(entry.at) } + 1
                list.add(idx, entry)
            }
        }
        override suspend fun list(incidentId: String): List<TimelineEntry> = timelineByIncident[incidentId]?.toList() ?: emptyList()
    }

    val alerts = object : AlertRepository {
        override suspend fun get(id: String) = alertsC.map[id]
        override suspend fun latestByFingerprint(fingerprint: String) = alertByFingerprint[fingerprint]?.let { alertsC.map[it] }

        override suspend fun save(alert: Alert, expectedVersion: Long?) = synchronized(this) {
            val cur = alertsC.map[alert.id]
            if (expectedVersion == null && cur != null) throw Conflict("Alert exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The alert changed. Reload and try again.")
            alertsC.put(alert.id, alert)
            indexAlert(alert)
        }

        override suspend fun list(filter: AlertFilter, cursor: String?, limit: Int): Page<Alert> {
            val src = if (cursor != null) alertOrder.tailMap(cursor, false) else alertOrder
            val seq = src.values.asSequence().mapNotNull { alertsC.map[it] }.filter { matches(it, filter) }
            return page(seq, limit) { alertKey(it) }
        }

        override suspend fun scan(filter: AlertFilter, consumer: (Alert) -> Unit) =
            alertOrder.values.asSequence().mapNotNull { alertsC.map[it] }.filter { matches(it, filter) }.forEach(consumer)

        override suspend fun count(filter: AlertFilter) = alertsC.map.values.count { matches(it, filter) }.toLong()

        private fun matches(a: Alert, f: AlertFilter): Boolean {
            if (f.statuses.isNotEmpty() && a.status !in f.statuses) return false
            if (f.severities.isNotEmpty() && a.severity !in f.severities) return false
            if (f.teamIds.isNotEmpty() && a.teamId !in f.teamIds) return false
            if (!inRange(a.firstSeenAt, f.from, f.to)) return false
            val q = f.query?.trim()?.lowercase()
            if (!q.isNullOrEmpty() && !a.title.lowercase().contains(q)) return false
            return true
        }
    }

    val agents = object : AgentRepository {
        override suspend fun get(id: String) = agentsC.map[id]
        override suspend fun list() = agentsC.map.values.toList()
        override suspend fun save(agent: AgentConfig) = agentsC.put(agent.id, agent)
        override suspend fun delete(id: String) = agentsC.remove(id)
    }

    val runs = object : RunRepository {
        override suspend fun get(id: String) = runsC.map[id]

        override suspend fun save(run: AgentRun, expectedVersion: Long?) = synchronized(this) {
            val cur = runsC.map[run.id]
            if (expectedVersion == null && cur != null) throw Conflict("Run exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The run changed")
            runsC.put(run.id, run)
        }

        override suspend fun bySubject(subjectId: String) = runsC.map.values.filter { it.subjectId == subjectId }
        override suspend fun recent(limit: Int) = runsC.map.values.sortedByDescending { it.createdAt }.take(limit)
        override suspend fun active() = runsC.map.values.filter { it.status.active }

        override suspend fun claim(agentId: String, limit: Int, at: Instant): List<AgentRun> = synchronized(this) {
            runsC.map.values.filter { it.agentId == agentId && it.status == RunStatus.QUEUED }
                .sortedBy { it.createdAt }.take(limit)
                .map { r -> r.copy(status = RunStatus.DISPATCHED, startedAt = at, version = r.version + 1).also { runsC.put(it.id, it) } }
        }
    }

    val mcpServers = object : McpServerRepository {
        override suspend fun get(id: String) = mcpC.map[id]
        override suspend fun list() = mcpC.map.values.toList()
        override suspend fun save(server: McpServerConfig) = mcpC.put(server.id, server)
        override suspend fun delete(id: String) = mcpC.remove(id)
    }

    val schedules = object : ScheduleRepository {
        override suspend fun get(id: String) = schedulesC.map[id]
        override suspend fun byTeam(teamId: String) = schedulesC.map.values.filter { it.teamId == teamId }
        override suspend fun list() = schedulesC.map.values.toList()
        override suspend fun delete(id: String) = schedulesC.remove(id)
        override suspend fun save(schedule: Schedule, expectedVersion: Long?) = synchronized(this) {
            val cur = schedulesC.map[schedule.id]
            if (expectedVersion == null && cur != null) throw Conflict("Schedule exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The schedule changed. Reload and try again.")
            schedulesC.put(schedule.id, schedule)
        }
    }

    val overrides = object : OverrideRepository {
        override suspend fun get(id: String) = overridesC.map[id]
        override suspend fun save(override: ScheduleOverride) = overridesC.put(override.id, override)
        override suspend fun delete(id: String) = overridesC.remove(id)
        override suspend fun overlapping(scheduleId: String, from: Instant, to: Instant) = overridesC.map.values
            .filter { it.scheduleId == scheduleId && it.startsAt < to && it.endsAt > from }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        override suspend fun endingAfter(teamId: String, userId: String, after: Instant) =
            overridesC.map.values.filter { it.teamId == teamId && it.userId == userId && it.endsAt > after }
        override suspend fun deleteBySchedule(scheduleId: String) =
            overridesC.map.values.filter { it.scheduleId == scheduleId }.forEach { overridesC.remove(it.id) }
    }

    val escalationPolicies = object : EscalationPolicyRepository {
        override suspend fun get(id: String) = escalationPoliciesC.map[id]
        override suspend fun byTeam(teamId: String) = escalationPoliciesC.map.values.filter { it.teamId == teamId }
        override suspend fun delete(id: String) = escalationPoliciesC.remove(id)
        override suspend fun save(policy: EscalationPolicy, expectedVersion: Long?) = synchronized(this) {
            val cur = escalationPoliciesC.map[policy.id]
            if (expectedVersion == null && cur != null) throw Conflict("Escalation policy exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The escalation policy changed. Reload and try again.")
            escalationPoliciesC.put(policy.id, policy)
        }
    }

    val runbooks = object : RunbookRepository {
        override suspend fun get(id: String) = runbooksC.map[id]
        override suspend fun byTeam(teamId: String) = runbooksC.map.values.filter { it.teamId == teamId }
        override suspend fun delete(id: String) = runbooksC.remove(id)
        override suspend fun save(runbook: Runbook, expectedVersion: Long?) = synchronized(this) {
            val cur = runbooksC.map[runbook.id]
            if (expectedVersion == null && cur != null) throw Conflict("Runbook exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The runbook changed. Reload and try again.")
            runbooksC.put(runbook.id, runbook)
        }
    }

    val escalations = object : EscalationRepository {
        override suspend fun get(id: String) = escalationsC.map[id]
        override suspend fun save(escalation: Escalation, expectedVersion: Long?) = synchronized(this) {
            val cur = escalationsC.map[escalation.id]
            if (expectedVersion == null && cur != null) throw Conflict("Escalation exists")
            if (expectedVersion != null && cur?.version != expectedVersion) throw Conflict("The escalation changed")
            escalationsC.put(escalation.id, escalation)
        }
        override suspend fun due(at: Instant, limit: Int) = escalationsC.map.values.asSequence()
            .filter { it.status == EscalationStatus.ACTIVE && it.nextAt != null && it.nextAt <= at }
            .sortedWith(compareBy({ it.nextAt }, { it.id })).take(limit).toList()
        override suspend fun activeByPolicy(policyId: String) =
            escalationsC.map.values.filter { it.policyId == policyId && it.status == EscalationStatus.ACTIVE }
    }

    val groups = object : GroupRepository {
        override suspend fun get(id: String) = groupsC.map[id]
        override suspend fun list() = groupsC.map.values.toList()
        override suspend fun save(group: DirectoryGroup) = groupsC.put(group.id, group)
        override suspend fun delete(id: String) = groupsC.remove(id)
    }

    val config = object : ConfigRepository {
        override suspend fun llm() = configC.map["llm"]?.let { JSON.decodeFromJsonElement(LlmConfig.serializer(), it) }
        override suspend fun saveLlm(config: LlmConfig) = configC.put("llm", JSON.encodeToJsonElement(LlmConfig.serializer(), config))
    }
}
