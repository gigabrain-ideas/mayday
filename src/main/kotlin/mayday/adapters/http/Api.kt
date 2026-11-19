package mayday.adapters.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mayday.adapters.auth.OidcClient
import mayday.adapters.auth.Sessions
import mayday.adapters.mcp.McpServer
import mayday.agents.AgentAction
import mayday.agents.AgentInput
import mayday.agents.AgentService
import mayday.agents.LlmInput
import mayday.agents.McpServerInput
import mayday.agents.SubjectKind
import mayday.alerting.AlertFilter
import mayday.alerting.AlertService
import mayday.alerting.AlertSignal
import mayday.alerting.AlertStatus
import mayday.directory.DirectoryService
import mayday.identity.IdentityService
import mayday.identity.OrgRole
import mayday.identity.OwnerKind
import mayday.identity.Principal
import mayday.identity.TeamRole
import mayday.identity.TokenScope
import mayday.identity.User
import mayday.incidents.EntryKind
import mayday.incidents.IncidentFilter
import mayday.incidents.IncidentPatch
import mayday.incidents.IncidentService
import mayday.incidents.IncidentStatus
import mayday.incidents.NewIncident
import mayday.incidents.Postmortem
import mayday.policy.ChangeRequestStatus
import mayday.policy.Enforcement
import mayday.policy.PolicyService
import mayday.policy.Scope
import mayday.reporting.ReportQuery
import mayday.reporting.ReportService
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.JSON
import mayday.shared.Severity
import mayday.shared.Ts
import mayday.shared.Unauthenticated
import mayday.shared.Unavailable
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable data class AuthConfig(val sso: Boolean, val devLogin: Boolean, val ssoName: String)
@Serializable data class Me(val user: User, val actorKind: String, val scopes: Set<TokenScope>, val agentReady: Boolean)
@Serializable data class DevLogin(val email: String, val name: String = "")
@Serializable data class TimelineInput(val text: String, val at: Ts? = null)
@Serializable data class ResolutionInput(val notes: String)
@Serializable data class RunbookInput(val title: String = "", val body: String)
@Serializable data class RunInput(val action: AgentAction, val agentId: String? = null, val prompt: String = "")
@Serializable data class TextInput(val text: String = "")
@Serializable data class LinkInput(val incident: String)
@Serializable data class SettingInput(val scope: Scope, val scopeId: String = "org", val key: String, val value: String, val enforcement: Enforcement = Enforcement.NONE)
@Serializable data class ChangeRequestInput(val scope: Scope, val scopeId: String = "org", val key: String, val value: String, val reason: String = "")
@Serializable data class DecisionInput(val approve: Boolean, val note: String = "")
@Serializable data class ProjectInput(val name: String, val slug: String? = null, val description: String = "")
@Serializable data class TeamInput(val name: String, val slug: String? = null, val projectId: String? = null, val description: String = "")
@Serializable data class UserInput(val email: String, val name: String = "", val role: OrgRole = OrgRole.MEMBER)
@Serializable data class UserPatch(val name: String? = null, val role: OrgRole? = null, val teams: Map<String, TeamRole>? = null, val projectAdmin: Set<String>? = null, val disabled: Boolean? = null)
@Serializable data class TokenInput(val name: String, val scopes: Set<TokenScope> = setOf(TokenScope.READ), val ttlDays: Long? = null)
@Serializable data class CompleteInput(val success: Boolean, val output: String = "", val error: String = "")
@Serializable data class ClaimInput(val limit: Int = 5)
@Serializable data class Settings(val schema: List<mayday.policy.SettingDef>, val effective: List<mayday.policy.EffectiveSetting>, val raw: List<mayday.policy.SettingValue>)
@Serializable data class ExchangeResult(val access_token: String, val issued_token_type: String, val token_type: String, val expires_in: Long)

class Api(
    private val identity: IdentityService,
    private val directory: DirectoryService,
    private val policy: PolicyService,
    private val incidents: IncidentService,
    private val alerts: AlertService,
    private val agents: AgentService,
    private val reports: ReportService,
    private val mcp: McpServer,
    private val sessions: Sessions,
    private val oidc: OidcClient?,
    private val devLogin: Boolean,
    private val publicUrl: String,
    private val sse: SseHub,
) {
    val router = Router()

    fun principal(r: Request): Principal =
        principal(r.header("Authorization"), r.cookie(Sessions.COOKIE), r.method, r.header("X-Requested-With"))

    fun principal(auth: String?, cookie: String?, method: String, requestedWith: String?): Principal {
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            val token = auth.substring(7).trim()
            if (oidc != null && !token.startsWith("mdy_") && oidc.looksLikeOurs(token)) {
                val id = oidc.verifyAccessToken(token)
                return identity.principalForUser(identity.upsertSso(id.subject, id.email, id.name))
            }
            return identity.authenticate(token)
        }
        val userId = sessions.verify(cookie) ?: throw Unauthenticated()
        val user = runCatching { identity.user(userId) }.getOrNull() ?: throw Unauthenticated()
        if (method != "GET" && requestedWith != "mayday") throw Forbidden("Missing X-Requested-With header")
        return identity.principalForUser(user)
    }

    private fun Request.p() = principal(this)
    private fun Request.limit() = q("limit")?.toIntOrNull() ?: 50
    private fun Request.instant(name: String): Instant? = q(name)?.let { runCatching { Instant.parse(it) }.getOrElse { throw Invalid("'$name' must be an ISO-8601 time") } }
    private inline fun <reified E : Enum<E>> Request.enums(name: String): Set<E> =
        qList(name).map { v -> enumValues<E>().firstOrNull { it.name.equals(v, true) } ?: throw Invalid("Unknown $name '$v'") }.toSet()

    init {
        val r = router

        // ---- auth ----
        r.get("/api/v1/auth/config") { ok(AuthConfig(oidc != null, devLogin, "Single sign-on")) }
        r.get("/auth/login") { req ->
            val client = oidc ?: throw Unavailable("Single sign-on is not configured")
            val state = Ids.secret(18); val nonce = Ids.secret(18); val verifier = Ids.secret(48)
            val redirect = req.q("redirect")?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
            Redirect(client.authorizeUrl(state, nonce, verifier), mapOf("Set-Cookie" to sessions.cookie(Sessions.FLOW_COOKIE, sessions.flow(state, nonce, verifier, redirect), Duration.ofMinutes(10))))
        }
        r.get("/auth/callback") { req ->
            val client = oidc ?: throw Unavailable("Single sign-on is not configured")
            val flow = sessions.readFlow(req.cookie(Sessions.FLOW_COOKIE)) ?: throw Unauthenticated("The login took too long. Try again.")
            req.q("error")?.let { throw Unauthenticated("The identity provider refused the login: $it") }
            if (req.q("state") != flow.state) throw Unauthenticated("Invalid login state")
            val id = client.exchange(req.q("code") ?: throw Invalid("Missing code"), flow.verifier, flow.nonce)
            val user = identity.upsertSso(id.subject, id.email, id.name)
            Redirect(flow.redirect, mapOf("Set-Cookie" to sessions.sessionCookie(user.id)))
        }
        r.post("/api/v1/auth/dev-login") { req ->
            if (!devLogin) throw Forbidden("Development login is turned off")
            val b = req.json<DevLogin>()
            val user = identity.upsertSso("dev|${b.email.lowercase()}", b.email, b.name)
            ok(user, headers = mapOf("Set-Cookie" to sessions.sessionCookie(user.id)))
        }
        r.get("/auth/dev-login") { req ->
            if (!devLogin) throw Forbidden("Development login is turned off")
            val email = req.q("email") ?: throw Invalid("email is required")
            val user = identity.upsertSso("dev|${email.lowercase()}", email, "")
            val redirect = req.q("redirect")?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: "/"
            Redirect(redirect, mapOf("Set-Cookie" to sessions.sessionCookie(user.id)))
        }
        r.post("/api/v1/auth/logout") { _ ->
            ok(mapOf("logoutUrl" to (oidc?.logoutUrl(publicUrl) ?: "/")), headers = mapOf("Set-Cookie" to sessions.clear(Sessions.COOKIE)))
        }
        r.get("/api/v1/me") { req ->
            val p = req.p()
            val u = p.user ?: throw Forbidden("Agent identities have no profile; use /api/v1/whoami")
            ok(Me(u, p.actor.kind.name, p.scopes, agents.internalReady()))
        }
        r.get("/api/v1/whoami") { req ->
            val p = req.p()
            ok(mapOf("actor" to p.actor.name, "kind" to p.actor.kind.name, "role" to p.role.name, "onBehalfOf" to (p.user?.email ?: ""), "scopes" to p.scopes.joinToString(" ")))
        }
        r.post("/oauth/token") { req ->
            val form = Request.parseQuery(req.body()).mapValues { it.value.first() }
            if (form["grant_type"] != "urn:ietf:params:oauth:grant-type:token-exchange") throw Invalid("unsupported_grant_type")
            val subjectToken = form["subject_token"] ?: throw Invalid("subject_token is required")
            val actorToken = form["actor_token"] ?: throw Invalid("actor_token is required")
            val subject = principal("Bearer $subjectToken", null, "GET", null)
            val actor = identity.authenticate(actorToken)
            val ttl = Duration.ofHours(1)
            ok(ExchangeResult(identity.exchange(subject, actor, ttl), "urn:ietf:params:oauth:token-type:access_token", "Bearer", ttl.seconds))
        }

        // ---- incidents ----
        r.get("/api/v1/incidents") { req ->
            val f = IncidentFilter(
                statuses = if (req.q("open") == "true") IncidentStatus.entries.filter { it.open }.toSet() else req.enums("status"),
                severities = req.enums("severity"),
                teamIds = req.qList("team").map { directory.team(it).id }.toSet(),
                query = req.q("q"), from = req.instant("from"), to = req.instant("to"),
            )
            ok(incidents.list(req.p(), f, req.q("cursor"), req.limit()))
        }
        r.post("/api/v1/incidents") { req -> ok(incidents.declare(req.p(), req.json<NewIncident>()), 201) }
        r.get("/api/v1/incidents/:ref") { req -> ok(incidents.get(req.p(), req.param("ref"))) }
        r.patch("/api/v1/incidents/:ref") { req -> ok(incidents.update(req.p(), req.param("ref"), req.json<IncidentPatch>())) }
        r.get("/api/v1/incidents/:ref/timeline") { req -> ok(incidents.timeline(req.p(), req.param("ref"))) }
        r.post("/api/v1/incidents/:ref/timeline") { req ->
            val b = req.json<TimelineInput>()
            ok(incidents.addEntry(req.p(), req.param("ref"), b.text, EntryKind.NOTE, b.at), 201)
        }
        r.put("/api/v1/incidents/:ref/postmortem") { req -> ok(incidents.savePostmortem(req.p(), req.param("ref"), req.json<Postmortem>())) }
        r.put("/api/v1/incidents/:ref/resolution") { req -> ok(incidents.saveResolution(req.p(), req.param("ref"), req.json<ResolutionInput>().notes)) }
        r.put("/api/v1/incidents/:ref/runbook") { req -> val b = req.json<RunbookInput>(); ok(incidents.saveRunbook(req.p(), req.param("ref"), b.title, b.body)) }
        r.get("/api/v1/incidents/:ref/agent-runs") { req ->
            val p = req.p(); ok(agents.runsFor(p, incidents.get(p, req.param("ref")).id))
        }
        r.post("/api/v1/incidents/:ref/agent-runs") { req ->
            val b = req.json<RunInput>()
            ok(agents.trigger(req.p(), b.action, SubjectKind.INCIDENT, req.param("ref"), b.agentId, b.prompt), 202)
        }

        // ---- alerts ----
        r.get("/api/v1/alerts") { req ->
            val f = AlertFilter(req.enums("status"), req.enums("severity"), req.qList("team").map { directory.team(it).id }.toSet(),
                req.q("q"), req.instant("from"), req.instant("to"))
            ok(alerts.list(req.p(), f, req.q("cursor"), req.limit()))
        }
        r.post("/api/v1/alerts") { req -> val res = alerts.ingest(req.p(), req.json<AlertSignal>()); ok(res, if (res.created) 201 else 200) }
        r.post("/api/v1/integrations/alertmanager") { req -> ok(alertmanager(req)) }
        r.get("/api/v1/alerts/:id") { req -> ok(alerts.get(req.p(), req.param("id"))) }
        r.post("/api/v1/alerts/:id/acknowledge") { req -> ok(alerts.acknowledge(req.p(), req.param("id"))) }
        r.post("/api/v1/alerts/:id/resolve") { req -> ok(alerts.resolve(req.p(), req.param("id"), req.body().takeIf { it.isNotBlank() }?.let { JSON.decodeFromString(TextInput.serializer(), it).text }.orEmpty())) }
        r.post("/api/v1/alerts/:id/updates") { req -> ok(alerts.addUpdate(req.p(), req.param("id"), req.json<TextInput>().text)) }
        r.post("/api/v1/alerts/:id/link") { req -> ok(alerts.linkIncident(req.p(), req.param("id"), req.json<LinkInput>().incident)) }
        r.get("/api/v1/alerts/:id/agent-runs") { req -> ok(agents.runsFor(req.p(), req.param("id"))) }
        r.post("/api/v1/alerts/:id/agent-runs") { req ->
            val b = req.json<RunInput>()
            ok(agents.trigger(req.p(), b.action, SubjectKind.ALERT, req.param("id"), b.agentId, b.prompt), 202)
        }

        // ---- reports ----
        r.get("/api/v1/reports") { req ->
            val to = req.instant("to") ?: Instant.now()
            val from = req.instant("from") ?: to.minus(30, ChronoUnit.DAYS)
            ok(reports.report(req.p(), ReportQuery(req.qList("team"), from, to, req.enums<Severity>("severity").toList())))
        }

        // ---- directory ----
        r.get("/api/v1/projects") { req -> mayday.identity.Access.requireRead(req.p()); ok(directory.projects()) }
        r.post("/api/v1/projects") { req -> admin(req); val b = req.json<ProjectInput>(); ok(directory.createProject(b.name, b.slug, b.description), 201) }
        r.patch("/api/v1/projects/:id") { req -> admin(req); val b = req.json<ProjectInput>(); ok(directory.updateProject(req.param("id"), b.name, b.description)) }
        r.delete("/api/v1/projects/:id") { req -> admin(req); directory.deleteProject(req.param("id")); NoContent }
        r.get("/api/v1/teams") { req -> mayday.identity.Access.requireRead(req.p()); ok(directory.teams()) }
        r.post("/api/v1/teams") { req -> admin(req); val b = req.json<TeamInput>(); ok(directory.createTeam(b.name, b.slug, b.projectId, b.description), 201) }
        r.patch("/api/v1/teams/:id") { req -> admin(req); val b = req.json<TeamInput>(); ok(directory.updateTeam(req.param("id"), b.name, b.projectId, b.description)) }
        r.delete("/api/v1/teams/:id") { req -> admin(req); directory.deleteTeam(req.param("id")); NoContent }

        // ---- users and tokens ----
        r.get("/api/v1/users") { req -> mayday.identity.Access.requireRead(req.p()); ok(identity.list(req.q("q"), req.q("cursor"), req.limit())) }
        r.post("/api/v1/users") { req -> val b = req.json<UserInput>(); ok(identity.createUser(req.p(), b.email, b.name, b.role), 201) }
        r.patch("/api/v1/users/:id") { req ->
            val b = req.json<UserPatch>()
            ok(identity.updateUser(req.p(), req.param("id"), b.name, b.role, b.teams, b.projectAdmin, b.disabled))
        }
        r.get("/api/v1/tokens") { req ->
            val p = req.p(); val u = p.user ?: throw Forbidden("Only users have personal tokens")
            ok(identity.tokensOf(OwnerKind.USER, u.id))
        }
        r.post("/api/v1/tokens") { req -> val b = req.json<TokenInput>(); ok(identity.issueToken(req.p(), b.name, b.scopes, b.ttlDays?.let { Duration.ofDays(it) }), 201) }
        r.delete("/api/v1/tokens/:id") { req -> identity.revokeToken(req.p(), req.param("id")); NoContent }

        // ---- policy ----
        r.get("/api/v1/settings") { req ->
            val p = req.p(); mayday.identity.Access.requireRead(p)
            val scope = req.enums<Scope>("scope").firstOrNull() ?: Scope.ORG
            val id = when (scope) { Scope.ORG -> "org"; Scope.PROJECT -> directory.project(req.q("scopeId") ?: "").id; Scope.TEAM -> directory.team(req.q("scopeId") ?: "").id }
            ok(Settings(policy.schema(), policy.effectiveAt(scope, id), policy.raw(scope, id)))
        }
        r.put("/api/v1/settings") { req -> val b = req.json<SettingInput>(); ok(policy.set(req.p(), b.scope, b.scopeId, b.key, b.value, b.enforcement)) }
        r.delete("/api/v1/settings") { req ->
            val scope = req.enums<Scope>("scope").firstOrNull() ?: throw Invalid("scope is required")
            policy.clear(req.p(), scope, req.q("scopeId") ?: "org", req.q("key") ?: throw Invalid("key is required")); NoContent
        }
        r.get("/api/v1/change-requests") { req ->
            mayday.identity.Access.requireRead(req.p())
            ok(policy.changeRequests(req.enums<ChangeRequestStatus>("status").firstOrNull()))
        }
        r.post("/api/v1/change-requests") { req -> val b = req.json<ChangeRequestInput>(); ok(policy.requestChange(req.p(), b.scope, b.scopeId, b.key, b.value, b.reason), 201) }
        r.post("/api/v1/change-requests/:id/decision") { req -> val b = req.json<DecisionInput>(); ok(policy.decide(req.p(), req.param("id"), b.approve, b.note)) }

        // ---- agents ----
        r.get("/api/v1/agents") { req -> ok(agents.list(req.p())) }
        r.get("/api/v1/agents/eligible") { req ->
            mayday.identity.Access.requireRead(req.p())
            val action = req.enums<AgentAction>("action").firstOrNull() ?: throw Invalid("action is required")
            ok(agents.eligible(action))
        }
        r.post("/api/v1/agents") { req -> ok(agents.create(req.p(), req.json<AgentInput>()), 201) }
        r.get("/api/v1/agents/:id") { req -> ok(agents.get(req.p(), req.param("id"))) }
        r.put("/api/v1/agents/:id") { req -> ok(agents.update(req.p(), req.param("id"), req.json<AgentInput>())) }
        r.delete("/api/v1/agents/:id") { req -> agents.delete(req.p(), req.param("id")); NoContent }
        r.post("/api/v1/agents/:id/credentials") { req -> ok(agents.rotateCredentials(req.p(), req.param("id"))) }
        r.get("/api/v1/agent-runs") { req -> ok(agents.recentRuns(req.p(), req.limit())) }
        r.get("/api/v1/agent-runs/:id") { req -> ok(agents.run(req.p(), req.param("id"))) }
        r.post("/api/v1/agent-runs/claim") { req -> ok(agents.claim(req.p(), req.body().takeIf { it.isNotBlank() }?.let { JSON.decodeFromString(ClaimInput.serializer(), it).limit } ?: 5)) }
        r.post("/api/v1/agent-runs/:id/progress") { req -> ok(agents.progress(req.p(), req.param("id"), req.json<TextInput>().text)) }
        r.post("/api/v1/agent-runs/:id/complete") { req -> val b = req.json<CompleteInput>(); ok(agents.complete(req.p(), req.param("id"), b.success, b.output, b.error)) }
        r.post("/api/v1/agent-runs/:id/cancel") { req -> ok(agents.cancel(req.p(), req.param("id"))) }
        r.get("/api/v1/llm") { req -> ok(agents.llm(req.p())) }
        r.put("/api/v1/llm") { req -> ok(agents.updateLlm(req.p(), req.json<LlmInput>())) }
        r.get("/api/v1/mcp-servers") { req -> ok(agents.mcpServers(req.p())) }
        r.post("/api/v1/mcp-servers") { req -> ok(agents.saveMcpServer(req.p(), null, req.json<McpServerInput>()), 201) }
        r.put("/api/v1/mcp-servers/:id") { req -> ok(agents.saveMcpServer(req.p(), req.param("id"), req.json<McpServerInput>())) }
        r.delete("/api/v1/mcp-servers/:id") { req -> agents.deleteMcpServer(req.p(), req.param("id")); NoContent }

        // ---- MCP and live events ----
        r.post("/mcp") { req ->
            val p = req.p()
            val body = runCatching { JSON.parseToJsonElement(req.body()) }.getOrElse { throw Invalid("Parse error") }
            val out = mcp.handle(p, body)
            if (out == null) BytesResponse(ByteArray(0), "application/json", 202)
            else JsonResponse(JSON.encodeToString(JsonElement.serializer(), out))
        }
        r.get("/mcp") { _ -> JsonResponse(errorBody("method_not_allowed", "This server does not open a server-to-client stream"), 405, mapOf("Allow" to "POST")) }
        r.delete("/mcp") { _ -> NoContent }
        r.get("/api/v1/events") { req -> sse.serve(req.ex, req.p()); Handled }
    }

    private fun admin(req: Request) = mayday.identity.Access.requireAdmin(req.p())

    private fun alertmanager(req: Request): Map<String, Int> {
        val p = req.p()
        val body = JSON.parseToJsonElement(req.body()).jsonObject
        var created = 0; var updated = 0
        (body["alerts"] as? JsonArray).orEmpty().forEach { el ->
            val a = el.jsonObject
            val labels = (a["labels"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val ann = (a["annotations"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val team = req.q("team") ?: labels["team"] ?: throw Invalid("Set the 'team' label or the ?team= parameter")
            val res = alerts.ingest(p, AlertSignal(
                title = ann["summary"] ?: labels["alertname"] ?: "Alert",
                severity = severityOf(labels["severity"]),
                team = team,
                source = "alertmanager",
                fingerprint = (a["fingerprint"] as? JsonPrimitive)?.content,
                status = if ((a["status"] as? JsonPrimitive)?.content == "resolved") AlertStatus.RESOLVED else AlertStatus.FIRING,
                description = ann["description"].orEmpty(),
                labels = labels,
                externalUrl = (a["generatorURL"] as? JsonPrimitive)?.content,
            ))
            if (res.created) created++ else updated++
        }
        return mapOf("created" to created, "updated" to updated)
    }

    private fun severityOf(s: String?): Severity = when (s?.lowercase()) {
        "critical", "page", "p1", "sev1", "fatal" -> Severity.SEV1
        "high", "error", "major", "p2", "sev2" -> Severity.SEV2
        "warning", "warn", "medium", "p3", "sev3" -> Severity.SEV3
        "low", "minor", "p4", "sev4" -> Severity.SEV4
        "info", "informational", "p5", "sev5" -> Severity.SEV5
        else -> Severity.SEV3
    }

    fun loginUrl(redirect: String) = "/auth/login?redirect=" + URLEncoder.encode(redirect, Charsets.UTF_8)
}

