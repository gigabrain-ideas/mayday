package mayday.adapters.postgres

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
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
import mayday.shared.Conflict
import mayday.shared.JSON
import mayday.shared.Page
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/** JDBC access on the IO dispatcher. HikariCP pools the connections and resets them on return. */
class Pool(val dataSource: HikariDataSource) : AutoCloseable {
    suspend fun <T> withConnection(f: (Connection) -> T): T = withContext(Dispatchers.IO) { dataSource.connection.use(f) }

    /** A connection outside the pool, for long-lived use such as LISTEN. */
    fun open(): Connection = DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password)

    override fun close() = dataSource.close()

    companion object {
        fun of(url: String, user: String?, password: String?, size: Int) = Pool(HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = size
            poolName = "mayday"
        }))
    }
}

class PostgresStore(val pool: Pool) {
    init { migrate() }

    private fun migrate() = pool.dataSource.connection.use { c ->
        c.createStatement().use { s ->
            for (t in listOf("projects", "teams", "settings", "change_requests", "agents", "mcp_servers", "config")) {
                s.execute("create table if not exists $t (id text primary key, doc jsonb not null)")
            }
            s.execute("create table if not exists users (id text primary key, email text unique not null, external_id text unique, doc jsonb not null)")
            s.execute("create table if not exists tokens (id text primary key, hash text unique not null, owner_kind text not null, owner_id text not null, doc jsonb not null)")
            s.execute("create index if not exists tokens_owner on tokens (owner_kind, owner_id)")
            s.execute("create sequence if not exists incident_number")
            s.execute("""create table if not exists incidents (
                id text primary key, number bigint unique not null, status text not null, severity text not null,
                team_ids text[] not null, reported_at timestamptz not null, search text not null, version bigint not null, doc jsonb not null)""")
            s.execute("create index if not exists incidents_teams on incidents using gin (team_ids)")
            s.execute("create index if not exists incidents_reported on incidents (reported_at)")
            s.execute("create index if not exists incidents_status on incidents (status, number desc)")
            s.execute("create table if not exists timeline (id text primary key, incident_id text not null, at timestamptz not null, doc jsonb not null)")
            s.execute("create index if not exists timeline_incident on timeline (incident_id, at)")
            s.execute("""create table if not exists alerts (
                id text primary key, fingerprint text not null, status text not null, severity text not null, team_id text not null,
                first_seen_at timestamptz not null, search text not null, version bigint not null, doc jsonb not null)""")
            s.execute("create index if not exists alerts_fp on alerts (fingerprint, first_seen_at desc)")
            s.execute("create index if not exists alerts_order on alerts (first_seen_at desc, id desc)")
            s.execute("create index if not exists alerts_team on alerts (team_id, first_seen_at desc)")
            s.execute("""create table if not exists agent_runs (
                id text primary key, agent_id text not null, subject_id text not null, status text not null,
                created_at timestamptz not null, version bigint not null, doc jsonb not null)""")
            s.execute("create index if not exists runs_subject on agent_runs (subject_id)")
            s.execute("create index if not exists runs_queue on agent_runs (agent_id, status, created_at)")
            s.execute("create index if not exists runs_recent on agent_runs (created_at desc)")
        }
    }

    // ---- helpers ----

    private fun <T> enc(ser: KSerializer<T>, v: T) = JSON.encodeToString(ser, v)
    private fun <T> dec(ser: KSerializer<T>, rs: ResultSet, col: String = "doc") = JSON.decodeFromString(ser, rs.getString(col))
    private fun ts(i: Instant) = Timestamp.from(i)

    private fun PreparedStatement.bind(vararg args: Any?): PreparedStatement {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> setObject(i + 1, null)
                is Instant -> setTimestamp(i + 1, ts(a))
                is JsonDoc -> setObject(i + 1, a.json, java.sql.Types.OTHER)
                is Array<*> -> setArray(i + 1, connection.createArrayOf("text", a))
                else -> setObject(i + 1, a)
            }
        }
        return this
    }

    private class JsonDoc(val json: String)

    private suspend fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = pool.withConnection { c ->
        c.prepareStatement(sql).use { ps -> ps.bind(*args).executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }
    }

    private suspend fun exec(sql: String, vararg args: Any?): Int = pool.withConnection { c -> c.prepareStatement(sql).use { it.bind(*args).executeUpdate() } }

    private suspend fun <T> stream(sql: String, args: List<Any?>, ser: KSerializer<T>, consumer: (T) -> Unit) = pool.withConnection { c ->
        c.autoCommit = false
        c.prepareStatement(sql).use { ps ->
            ps.fetchSize = 1000
            ps.bind(*args.toTypedArray()).executeQuery().use { rs -> while (rs.next()) consumer(dec(ser, rs)) }
        }
        c.commit()
    }

    private inner class DocTable<T>(private val table: String, private val ser: KSerializer<T>) {
        suspend fun get(id: String) = query("select doc from $table where id = ?", id) { dec(ser, it) }.firstOrNull()
        suspend fun all() = query("select doc from $table") { dec(ser, it) }
        suspend fun put(id: String, v: T) = exec("insert into $table (id, doc) values (?, ?) on conflict (id) do update set doc = excluded.doc", id, JsonDoc(enc(ser, v)))
        suspend fun delete(id: String) = exec("delete from $table where id = ?", id)
    }

    private val projectsT = DocTable("projects", Project.serializer())
    private val teamsT = DocTable("teams", Team.serializer())
    private val settingsT = DocTable("settings", SettingValue.serializer())
    private val requestsT = DocTable("change_requests", ChangeRequest.serializer())
    private val agentsT = DocTable("agents", AgentConfig.serializer())
    private val mcpT = DocTable("mcp_servers", McpServerConfig.serializer())
    private val configT = DocTable("config", JsonElement.serializer())

    val projects = object : ProjectRepository {
        override suspend fun get(id: String) = projectsT.get(id)
        override suspend fun bySlug(slug: String) = query("select doc from projects where doc->>'slug' = ?", slug) { dec(Project.serializer(), it) }.firstOrNull()
        override suspend fun list() = projectsT.all()
        override suspend fun save(project: Project) { projectsT.put(project.id, project) }
        override suspend fun delete(id: String) { projectsT.delete(id) }
    }

    val teams = object : TeamRepository {
        override suspend fun get(id: String) = teamsT.get(id)
        override suspend fun bySlug(slug: String) = query("select doc from teams where doc->>'slug' = ?", slug) { dec(Team.serializer(), it) }.firstOrNull()
        override suspend fun list() = teamsT.all()
        override suspend fun save(team: Team) { teamsT.put(team.id, team) }
        override suspend fun delete(id: String) { teamsT.delete(id) }
    }

    val users = object : UserRepository {
        private val ser = User.serializer()
        override suspend fun get(id: String) = query("select doc from users where id = ?", id) { dec(ser, it) }.firstOrNull()
        override suspend fun byEmail(email: String) = query("select doc from users where email = ?", email.lowercase()) { dec(ser, it) }.firstOrNull()
        override suspend fun byExternalId(externalId: String) = query("select doc from users where external_id = ?", externalId) { dec(ser, it) }.firstOrNull()
        override suspend fun count() = query("select count(*) from users") { it.getLong(1) }.first()
        override suspend fun withAgentAdmin() = query("select doc from users where doc->>'allowAgentAdmin' = 'true'") { dec(ser, it) }
        override suspend fun list(query: String?, cursor: String?, limit: Int): Page<User> {
            val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "%${it.replace("%", "")}%" }
            val rows = query(
                "select email, doc from users where (?::text is null or email > ?) and (?::text is null or email like ? or lower(doc->>'name') like ?) order by email limit ?",
                cursor, cursor, q, q, q, limit + 1,
            ) { dec(ser, it) }
            return if (rows.size > limit) Page(rows.take(limit), rows[limit - 1].email) else Page(rows)
        }
        override suspend fun save(user: User) {
            try {
                exec("insert into users (id, email, external_id, doc) values (?, ?, ?, ?) on conflict (id) do update set email = excluded.email, external_id = excluded.external_id, doc = excluded.doc",
                    user.id, user.email, user.externalId, JsonDoc(enc(ser, user)))
            } catch (e: java.sql.SQLException) {
                if (e.sqlState == "23505") throw Conflict("A user with this email exists") else throw e
            }
        }
    }

    val tokens = object : TokenRepository {
        private val ser = ApiToken.serializer()
        override suspend fun byHash(hash: String) = query("select doc from tokens where hash = ?", hash) { dec(ser, it) }.firstOrNull()
        override suspend fun byOwner(kind: OwnerKind, ownerId: String) = query("select doc from tokens where owner_kind = ? and owner_id = ?", kind.name, ownerId) { dec(ser, it) }
        override suspend fun get(id: String) = query("select doc from tokens where id = ?", id) { dec(ser, it) }.firstOrNull()
        override suspend fun save(token: ApiToken) {
            exec("insert into tokens (id, hash, owner_kind, owner_id, doc) values (?, ?, ?, ?, ?) on conflict (id) do update set doc = excluded.doc",
                token.id, token.hash, token.ownerKind.name, token.ownerId, JsonDoc(enc(ser, token)))
        }
        override suspend fun delete(id: String) { exec("delete from tokens where id = ?", id) }
    }

    val settings = object : SettingRepository {
        override suspend fun all() = settingsT.all()
        override suspend fun save(value: SettingValue) { settingsT.put(value.id, value) }
        override suspend fun delete(id: String) { settingsT.delete(id) }
    }

    val changeRequests = object : ChangeRequestRepository {
        override suspend fun get(id: String) = requestsT.get(id)
        override suspend fun list(status: ChangeRequestStatus?) =
            if (status == null) requestsT.all() else query("select doc from change_requests where doc->>'status' = ?", status.name) { dec(ChangeRequest.serializer(), it) }
        override suspend fun save(request: ChangeRequest) { requestsT.put(request.id, request) }
    }

    private fun incidentWhere(f: IncidentFilter, args: MutableList<Any?>): String {
        val w = mutableListOf("true")
        if (f.statuses.isNotEmpty()) { w += "status = any(?)"; args.add(f.statuses.map { it.name }.toTypedArray()) }
        if (f.severities.isNotEmpty()) { w += "severity = any(?)"; args.add(f.severities.map { it.name }.toTypedArray()) }
        if (f.teamIds.isNotEmpty()) { w += "team_ids && ?"; args.add(f.teamIds.toTypedArray()) }
        f.from?.let { w += "reported_at >= ?"; args += it }
        f.to?.let { w += "reported_at < ?"; args += it }
        f.query?.trim()?.takeIf { it.isNotEmpty() }?.let { w += "search like ?"; args += "%${it.lowercase().replace("%", "")}%" }
        return w.joinToString(" and ")
    }

    val incidents = object : IncidentRepository {
        private val ser = Incident.serializer()
        override suspend fun nextNumber() = query("select nextval('incident_number')") { it.getLong(1) }.first()
        override suspend fun get(id: String) = query("select doc from incidents where id = ?", id) { dec(ser, it) }.firstOrNull()
        override suspend fun byNumber(number: Long) = query("select doc from incidents where number = ?", number) { dec(ser, it) }.firstOrNull()

        override suspend fun save(incident: Incident, expectedVersion: Long?) {
            val doc = JsonDoc(enc(ser, incident))
            val search = "${incident.key} ${incident.title}".lowercase()
            if (expectedVersion == null) {
                try {
                    exec("insert into incidents (id, number, status, severity, team_ids, reported_at, search, version, doc) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        incident.id, incident.number, incident.status.name, incident.severity.name, incident.teamIds.toTypedArray(), incident.reportedAt, search, incident.version, doc)
                } catch (e: java.sql.SQLException) {
                    if (e.sqlState == "23505") throw Conflict("Incident exists") else throw e
                }
            } else {
                val n = exec("update incidents set status = ?, severity = ?, team_ids = ?, search = ?, version = ?, doc = ? where id = ? and version = ?",
                    incident.status.name, incident.severity.name, incident.teamIds.toTypedArray(), search, incident.version, doc, incident.id, expectedVersion)
                if (n == 0) throw Conflict("The incident changed. Reload and try again.")
            }
        }

        override suspend fun list(filter: IncidentFilter, cursor: String?, limit: Int): Page<Incident> {
            val args = mutableListOf<Any?>()
            var where = incidentWhere(filter, args)
            cursor?.toLongOrNull()?.let { where += " and number < ?"; args += it }
            args += limit + 1
            val rows = query("select doc from incidents where $where order by number desc limit ?", *args.toTypedArray()) { dec(ser, it) }
            return if (rows.size > limit) Page(rows.take(limit), rows[limit - 1].number.toString()) else Page(rows)
        }

        override suspend fun scan(filter: IncidentFilter, consumer: (Incident) -> Unit) {
            val args = mutableListOf<Any?>()
            stream("select doc from incidents where ${incidentWhere(filter, args)} order by number desc", args, ser, consumer)
        }
    }

    val timeline = object : TimelineRepository {
        private val ser = TimelineEntry.serializer()
        override suspend fun append(entry: TimelineEntry) { exec("insert into timeline (id, incident_id, at, doc) values (?, ?, ?, ?)", entry.id, entry.incidentId, entry.at, JsonDoc(enc(ser, entry))) }
        override suspend fun list(incidentId: String) = query("select doc from timeline where incident_id = ? order by at, id", incidentId) { dec(ser, it) }
    }

    private fun alertWhere(f: AlertFilter, args: MutableList<Any?>): String {
        val w = mutableListOf("true")
        if (f.statuses.isNotEmpty()) { w += "status = any(?)"; args.add(f.statuses.map { it.name }.toTypedArray()) }
        if (f.severities.isNotEmpty()) { w += "severity = any(?)"; args.add(f.severities.map { it.name }.toTypedArray()) }
        if (f.teamIds.isNotEmpty()) { w += "team_id = any(?)"; args.add(f.teamIds.toTypedArray()) }
        f.from?.let { w += "first_seen_at >= ?"; args += it }
        f.to?.let { w += "first_seen_at < ?"; args += it }
        f.query?.trim()?.takeIf { it.isNotEmpty() }?.let { w += "search like ?"; args += "%${it.lowercase().replace("%", "")}%" }
        return w.joinToString(" and ")
    }

    val alerts = object : AlertRepository {
        private val ser = Alert.serializer()
        override suspend fun get(id: String) = query("select doc from alerts where id = ?", id) { dec(ser, it) }.firstOrNull()
        override suspend fun latestByFingerprint(fingerprint: String) =
            query("select doc from alerts where fingerprint = ? order by first_seen_at desc limit 1", fingerprint) { dec(ser, it) }.firstOrNull()

        override suspend fun save(alert: Alert, expectedVersion: Long?) {
            val doc = JsonDoc(enc(ser, alert))
            if (expectedVersion == null) {
                try {
                    exec("insert into alerts (id, fingerprint, status, severity, team_id, first_seen_at, search, version, doc) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        alert.id, alert.fingerprint, alert.status.name, alert.severity.name, alert.teamId, alert.firstSeenAt, alert.title.lowercase(), alert.version, doc)
                } catch (e: java.sql.SQLException) {
                    if (e.sqlState == "23505") throw Conflict("Alert exists") else throw e
                }
            } else {
                val n = exec("update alerts set status = ?, severity = ?, version = ?, doc = ? where id = ? and version = ?",
                    alert.status.name, alert.severity.name, alert.version, doc, alert.id, expectedVersion)
                if (n == 0) throw Conflict("The alert changed. Reload and try again.")
            }
        }

        override suspend fun list(filter: AlertFilter, cursor: String?, limit: Int): Page<Alert> {
            val args = mutableListOf<Any?>()
            var where = alertWhere(filter, args)
            if (cursor != null) {
                val at = runCatching { Instant.parse(cursor.substringBefore('|')) }.getOrNull()
                if (at != null) { where += " and (first_seen_at, id) < (?, ?)"; args += at; args += cursor.substringAfter('|') }
            }
            args += limit + 1
            val rows = query("select doc from alerts where $where order by first_seen_at desc, id desc limit ?", *args.toTypedArray()) { dec(ser, it) }
            return if (rows.size > limit) Page(rows.take(limit), "${rows[limit - 1].firstSeenAt}|${rows[limit - 1].id}") else Page(rows)
        }

        override suspend fun scan(filter: AlertFilter, consumer: (Alert) -> Unit) {
            val args = mutableListOf<Any?>()
            stream("select doc from alerts where ${alertWhere(filter, args)}", args, ser, consumer)
        }
    }

    val agents = object : AgentRepository {
        override suspend fun get(id: String) = agentsT.get(id)
        override suspend fun list() = agentsT.all()
        override suspend fun save(agent: AgentConfig) { agentsT.put(agent.id, agent) }
        override suspend fun delete(id: String) { agentsT.delete(id) }
    }

    val runs = object : RunRepository {
        private val ser = AgentRun.serializer()
        override suspend fun get(id: String) = query("select doc from agent_runs where id = ?", id) { dec(ser, it) }.firstOrNull()

        override suspend fun save(run: AgentRun, expectedVersion: Long?) {
            val doc = JsonDoc(enc(ser, run))
            if (expectedVersion == null) {
                try {
                    exec("insert into agent_runs (id, agent_id, subject_id, status, created_at, version, doc) values (?, ?, ?, ?, ?, ?, ?)",
                        run.id, run.agentId, run.subjectId, run.status.name, run.createdAt, run.version, doc)
                } catch (e: java.sql.SQLException) {
                    if (e.sqlState == "23505") throw Conflict("Run exists") else throw e
                }
            } else {
                val n = exec("update agent_runs set status = ?, version = ?, doc = ? where id = ? and version = ?", run.status.name, run.version, doc, run.id, expectedVersion)
                if (n == 0) throw Conflict("The run changed")
            }
        }

        override suspend fun bySubject(subjectId: String) = query("select doc from agent_runs where subject_id = ?", subjectId) { dec(ser, it) }
        override suspend fun recent(limit: Int) = query("select doc from agent_runs order by created_at desc limit ?", limit) { dec(ser, it) }
        override suspend fun active() = query("select doc from agent_runs where status in ('QUEUED','DISPATCHED','RUNNING')") { dec(ser, it) }

        override suspend fun claim(agentId: String, limit: Int, at: Instant): List<AgentRun> = pool.withConnection { c ->
            c.autoCommit = false
            val claimed = c.prepareStatement(
                "select doc from agent_runs where agent_id = ? and status = 'QUEUED' order by created_at limit ? for update skip locked",
            ).use { ps -> ps.bind(agentId, limit).executeQuery().use { rs -> buildList { while (rs.next()) add(dec(ser, rs)) } } }
                .map { it.copy(status = RunStatus.DISPATCHED, startedAt = at, version = it.version + 1) }
            c.prepareStatement("update agent_runs set status = ?, version = ?, doc = ? where id = ?").use { ps ->
                claimed.forEach { r -> ps.bind(r.status.name, r.version, JsonDoc(enc(ser, r)), r.id); ps.addBatch() }
                if (claimed.isNotEmpty()) ps.executeBatch()
            }
            c.commit()
            claimed
        }
    }

    val mcpServers = object : McpServerRepository {
        override suspend fun get(id: String) = mcpT.get(id)
        override suspend fun list() = mcpT.all()
        override suspend fun save(server: McpServerConfig) { mcpT.put(server.id, server) }
        override suspend fun delete(id: String) { mcpT.delete(id) }
    }

    val config = object : ConfigRepository {
        override suspend fun llm() = configT.get("llm")?.let { JSON.decodeFromJsonElement(LlmConfig.serializer(), it) }
        override suspend fun saveLlm(config: LlmConfig) { configT.put("llm", JSON.encodeToJsonElement(LlmConfig.serializer(), config)) }
    }
}
