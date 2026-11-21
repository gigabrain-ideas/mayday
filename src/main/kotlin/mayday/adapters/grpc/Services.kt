package mayday.adapters.grpc

import com.google.protobuf.Empty
import com.google.protobuf.Timestamp
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import mayday.App
import mayday.MaydayProperties
import mayday.adapters.auth.Authenticator
import mayday.api.v1.*
import mayday.identity.Access
import mayday.shared.DomainEvent
import mayday.shared.Forbidden
import mayday.shared.Invalid
import mayday.shared.Unavailable
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import mayday.agents.AgentAction as DAgentAction
import mayday.agents.SubjectKind as DSubjectKind
import mayday.alerting.AlertStatus as DAlertStatus
import mayday.identity.OrgRole as DOrgRole
import mayday.identity.OwnerKind as DOwnerKind
import mayday.identity.TeamRole as DTeamRole
import mayday.identity.TokenScope as DTokenScope
import mayday.incidents.IncidentStatus as DIncidentStatus
import mayday.policy.ChangeRequestStatus as DChangeRequestStatus
import mayday.policy.Enforcement as DEnforcement
import mayday.policy.Scope as DScope
import mayday.shared.Severity as DSeverity

private val EMPTY: Empty = Empty.getDefaultInstance()

private fun Int.orDefault(d: Int) = takeIf { it > 0 } ?: d
private fun Timestamp.instant(present: Boolean): Instant? = if (present) ProtoJson.instant(this) else null
private fun String.orNull() = takeIf { it.isNotBlank() }

@Serializable private data class MeView(val user: mayday.identity.User, val actorKind: String, val scopes: Set<DTokenScope>, val agentReady: Boolean, val traceUrlTemplate: String, val agentAdminOrgAllowed: Boolean)
@Serializable private data class SettingsViewData(
    val schema: List<mayday.policy.SettingDef>,
    val effective: List<mayday.policy.EffectiveSetting>,
    val raw: List<mayday.policy.SettingValue>,
)

@Service
class AuthGrpc(private val auth: Authenticator, private val app: App, private val props: MaydayProperties) : AuthServiceGrpcKt.AuthServiceCoroutineImplBase() {
    override suspend fun getAuthConfig(request: Empty): AuthConfig =
        AuthConfig.newBuilder().setSso(auth.oidc != null).setDevLogin(auth.devLogin).setSsoName("Single sign-on").build()

    override suspend fun getMe(request: Empty): Me {
        val p = auth.caller()
        val u = p.user ?: throw Forbidden("Agent identities have no profile; use WhoAmI")
        return MeView(u, p.actor.kind.name, p.scopes, app.agents.internalReady(), props.traceUrlTemplate.trim(), app.identity.agentAdminOrgAllowed()).into(Me.newBuilder()).build()
    }

    override suspend fun whoAmI(request: Empty): WhoAmIResponse {
        val p = auth.caller()
        return WhoAmIResponse.newBuilder().setActor(p.actor.name).setKind(p.actor.kind.name).setRole(p.role.name)
            .setOnBehalfOf(p.user?.email ?: "").setScopes(p.scopes.joinToString(" ")).build()
    }
}

@Service
class UserGrpc(private val auth: Authenticator, private val app: App) : UserServiceGrpcKt.UserServiceCoroutineImplBase() {
    private val identity get() = app.identity

    override suspend fun listUsers(request: ListUsersRequest): UserPage {
        Access.requireRead(auth.caller())
        val q = if (request.hasQ()) request.q else null
        val cursor = if (request.hasCursor()) request.cursor else null
        return identity.list(q, cursor, request.limit.orDefault(50)).into(UserPage.newBuilder()).build()
    }

    override suspend fun createUser(request: CreateUserRequest): User =
        identity.createUser(auth.caller(), request.email, request.name, request.role.toDomain<DOrgRole>() ?: DOrgRole.MEMBER).into(User.newBuilder()).build()

    override suspend fun updateUser(request: UpdateUserRequest): User = identity.updateUser(
        auth.caller(), request.id,
        name = if (request.hasName()) request.name else null,
        role = request.role.toDomain<DOrgRole>(),
        teamsUpdate = if (request.replaceTeams) request.teamsMap.mapValues { it.value.toDomain<DTeamRole>() ?: throw Invalid("Team role is required") } else null,
        projectAdmin = if (request.replaceProjectAdmin) request.projectAdminList.toSet() else null,
        disabled = if (request.hasDisabled()) request.disabled else null,
    ).into(User.newBuilder()).build()

    override suspend fun listTokens(request: Empty): ApiTokens {
        val u = auth.caller().user ?: throw Forbidden("Only users have personal tokens")
        return identity.tokensOf(DOwnerKind.USER, u.id).itemsInto(ApiTokens.newBuilder()).build()
    }

    override suspend fun issueToken(request: IssueTokenRequest): IssuedToken {
        val scopes = request.scopesList.mapNotNull { it.toDomain<DTokenScope>() }.toSet().ifEmpty { setOf(DTokenScope.READ) }
        val ttl = if (request.hasTtlDays()) Duration.ofDays(request.ttlDays) else null
        return identity.issueToken(auth.caller(), request.name, scopes, ttl).into(IssuedToken.newBuilder()).build()
    }

    override suspend fun revokeToken(request: IdRequest): Empty = EMPTY.also { identity.revokeToken(auth.caller(), request.id) }

    override suspend fun setAgentAdmin(request: SetAgentAdminRequest): User =
        identity.setAgentAdmin(auth.caller(), request.allow).into(User.newBuilder()).build()
}

@Service
class DirectoryGrpc(private val auth: Authenticator, private val app: App) : DirectoryServiceGrpcKt.DirectoryServiceCoroutineImplBase() {
    private val directory get() = app.directory
    private suspend fun admin() = Access.requireAdmin(auth.caller())

    override suspend fun listProjects(request: Empty): Projects {
        Access.requireRead(auth.caller())
        return directory.projects().itemsInto(Projects.newBuilder()).build()
    }

    override suspend fun createProject(request: SaveProjectRequest): Project {
        admin()
        return directory.createProject(request.name, if (request.hasSlug()) request.slug else null, request.description).into(Project.newBuilder()).build()
    }

    override suspend fun updateProject(request: SaveProjectRequest): Project {
        admin()
        return directory.updateProject(request.id, request.name, request.description).into(Project.newBuilder()).build()
    }

    override suspend fun deleteProject(request: IdRequest): Empty = EMPTY.also { admin(); directory.deleteProject(request.id) }

    override suspend fun listTeams(request: Empty): Teams {
        Access.requireRead(auth.caller())
        return directory.teams().itemsInto(Teams.newBuilder()).build()
    }

    override suspend fun createTeam(request: SaveTeamRequest): Team {
        admin()
        val project = if (request.hasProjectId()) request.projectId.orNull() else null
        return directory.createTeam(request.name, if (request.hasSlug()) request.slug else null, project, request.description).into(Team.newBuilder()).build()
    }

    override suspend fun updateTeam(request: SaveTeamRequest): Team {
        admin()
        val project = if (request.hasProjectId()) request.projectId else null
        return directory.updateTeam(request.id, request.name, project, request.description).into(Team.newBuilder()).build()
    }

    override suspend fun deleteTeam(request: IdRequest): Empty = EMPTY.also { admin(); directory.deleteTeam(request.id) }
}

@Service
class IncidentGrpc(private val auth: Authenticator, private val app: App) : IncidentServiceGrpcKt.IncidentServiceCoroutineImplBase() {
    private val incidents get() = app.incidents

    override suspend fun listIncidents(request: ListIncidentsRequest): IncidentPage {
        val p = auth.caller()
        val filter = mayday.incidents.IncidentFilter(
            statuses = if (request.open) DIncidentStatus.entries.filter { it.open }.toSet()
            else request.statusList.mapNotNull { it.toDomain<DIncidentStatus>() }.toSet(),
            severities = request.severityList.mapNotNull { it.toDomain<DSeverity>() }.toSet(),
            teamIds = request.teamList.map { app.directory.team(it).id }.toSet(),
            query = if (request.hasQ()) request.q else null,
            from = request.from.instant(request.hasFrom()),
            to = request.to.instant(request.hasTo()),
        )
        return incidents.list(p, filter, if (request.hasCursor()) request.cursor else null, request.limit.orDefault(50))
            .into(IncidentPage.newBuilder()).build()
    }

    override suspend fun declareIncident(request: DeclareIncidentRequest): Incident =
        incidents.declare(auth.caller(), request.decode<mayday.incidents.NewIncident>()).into(Incident.newBuilder()).build()

    override suspend fun getIncident(request: IncidentRef): Incident = incidents.get(auth.caller(), request.ref).into(Incident.newBuilder()).build()

    override suspend fun updateIncident(request: UpdateIncidentRequest): Incident {
        val m = request.patch
        val patch = mayday.incidents.IncidentPatch(
            title = if (m.hasTitle()) m.title else null,
            description = if (m.hasDescription()) m.description else null,
            severity = m.severity.toDomain<DSeverity>(),
            status = m.status.toDomain<DIncidentStatus>(),
            teamIds = m.teamIdsList.ifEmpty { null },
            commanderId = if (m.hasCommanderId()) m.commanderId else null,
            impactStartedAt = m.impactStartedAt.instant(m.hasImpactStartedAt()),
            impactEndedAt = m.impactEndedAt.instant(m.hasImpactEndedAt()),
            discoveredAt = m.discoveredAt.instant(m.hasDiscoveredAt()),
            labels = if (m.replaceLabels || m.labelsCount > 0) m.labelsMap else null,
            postmortemRequired = if (m.hasPostmortemRequired()) m.postmortemRequired else null,
            version = if (m.hasVersion()) m.version else null,
        )
        return incidents.update(auth.caller(), request.ref, patch).into(Incident.newBuilder()).build()
    }

    override suspend fun listTimeline(request: IncidentRef): TimelineEntries =
        incidents.timeline(auth.caller(), request.ref).itemsInto(TimelineEntries.newBuilder()).build()

    override suspend fun addTimelineEntry(request: AddTimelineEntryRequest): TimelineEntry =
        incidents.addEntry(auth.caller(), request.ref, request.text, at = request.at.instant(request.hasAt())).into(TimelineEntry.newBuilder()).build()

    override suspend fun savePostmortem(request: SavePostmortemRequest): Incident {
        if (!request.hasPostmortem()) throw Invalid("postmortem is required")
        return incidents.savePostmortem(auth.caller(), request.ref, request.postmortem.decode()).into(Incident.newBuilder()).build()
    }

    override suspend fun saveResolution(request: SaveResolutionRequest): Incident =
        incidents.saveResolution(auth.caller(), request.ref, request.notes).into(Incident.newBuilder()).build()

    override suspend fun saveRunbook(request: SaveRunbookRequest): Incident =
        incidents.saveRunbook(auth.caller(), request.ref, request.title, request.body).into(Incident.newBuilder()).build()
}

@Service
class AlertGrpc(private val auth: Authenticator, private val app: App) : AlertServiceGrpcKt.AlertServiceCoroutineImplBase() {
    private val alerts get() = app.alerts

    override suspend fun listAlerts(request: ListAlertsRequest): AlertPage {
        val p = auth.caller()
        val filter = mayday.alerting.AlertFilter(
            statuses = request.statusList.mapNotNull { it.toDomain<DAlertStatus>() }.toSet(),
            severities = request.severityList.mapNotNull { it.toDomain<DSeverity>() }.toSet(),
            teamIds = request.teamList.map { app.directory.team(it).id }.toSet(),
            query = if (request.hasQ()) request.q else null,
            from = request.from.instant(request.hasFrom()),
            to = request.to.instant(request.hasTo()),
        )
        return alerts.list(p, filter, if (request.hasCursor()) request.cursor else null, request.limit.orDefault(50))
            .into(AlertPage.newBuilder()).build()
    }

    override suspend fun ingestAlert(request: AlertSignal): IngestResult {
        val signal = request.decode<mayday.alerting.AlertSignal>().let { it.copy(source = it.source.ifBlank { "api" }) }
        return alerts.ingest(auth.caller(), signal).into(IngestResult.newBuilder()).build()
    }

    override suspend fun getAlert(request: IdRequest): Alert = alerts.get(auth.caller(), request.id).into(Alert.newBuilder()).build()

    override suspend fun acknowledgeAlert(request: IdRequest): Alert = alerts.acknowledge(auth.caller(), request.id).into(Alert.newBuilder()).build()

    override suspend fun resolveAlert(request: AlertTextRequest): Alert = alerts.resolve(auth.caller(), request.id, request.text).into(Alert.newBuilder()).build()

    override suspend fun addAlertUpdate(request: AlertTextRequest): Alert = alerts.addUpdate(auth.caller(), request.id, request.text).into(Alert.newBuilder()).build()

    override suspend fun linkAlert(request: LinkAlertRequest): Alert = alerts.linkIncident(auth.caller(), request.id, request.incident).into(Alert.newBuilder()).build()
}

@Service
class ReportGrpc(private val auth: Authenticator, private val app: App) : ReportServiceGrpcKt.ReportServiceCoroutineImplBase() {
    override suspend fun getReport(request: ReportRequest): Report {
        val to = request.to.instant(request.hasTo()) ?: Instant.now()
        val from = request.from.instant(request.hasFrom()) ?: to.minus(30, ChronoUnit.DAYS)
        val query = mayday.reporting.ReportQuery(request.teamList, from, to, request.severityList.mapNotNull { it.toDomain<DSeverity>() })
        return app.reports.report(auth.caller(), query).into(Report.newBuilder()).build()
    }
}

@Service
class PolicyGrpc(private val auth: Authenticator, private val app: App) : PolicyServiceGrpcKt.PolicyServiceCoroutineImplBase() {
    private val policy get() = app.policy

    override suspend fun getSettings(request: GetSettingsRequest): SettingsView {
        Access.requireRead(auth.caller())
        val scope = request.scope.toDomain<DScope>() ?: DScope.ORG
        val id = when (scope) {
            DScope.ORG -> mayday.policy.ORG_ID
            DScope.PROJECT -> app.directory.project(request.scopeId).id
            DScope.TEAM -> app.directory.team(request.scopeId).id
        }
        return SettingsViewData(policy.schema(), policy.effectiveAt(scope, id), policy.raw(scope, id)).into(SettingsView.newBuilder()).build()
    }

    override suspend fun setSetting(request: SetSettingRequest): SettingValue {
        val scope = request.scope.toDomain<DScope>() ?: throw Invalid("scope is required")
        val enforcement = request.enforcement.toDomain<DEnforcement>() ?: DEnforcement.NONE
        return policy.set(auth.caller(), scope, request.scopeId.ifBlank { mayday.policy.ORG_ID }, request.key, request.value, enforcement)
            .into(SettingValue.newBuilder()).build()
    }

    override suspend fun clearSetting(request: ClearSettingRequest): Empty {
        val scope = request.scope.toDomain<DScope>() ?: throw Invalid("scope is required")
        if (request.key.isBlank()) throw Invalid("key is required")
        policy.clear(auth.caller(), scope, request.scopeId.ifBlank { mayday.policy.ORG_ID }, request.key)
        return EMPTY
    }

    override suspend fun listChangeRequests(request: ListChangeRequestsRequest): ChangeRequests {
        Access.requireRead(auth.caller())
        return policy.changeRequests(request.status.toDomain<DChangeRequestStatus>()).itemsInto(ChangeRequests.newBuilder()).build()
    }

    override suspend fun requestChange(request: RequestChangeRequest): ChangeRequest {
        val scope = request.scope.toDomain<DScope>() ?: throw Invalid("scope is required")
        return policy.requestChange(auth.caller(), scope, request.scopeId.ifBlank { mayday.policy.ORG_ID }, request.key, request.value, request.reason)
            .into(ChangeRequest.newBuilder()).build()
    }

    override suspend fun decideChange(request: DecideChangeRequest): ChangeRequest =
        policy.decide(auth.caller(), request.id, request.approve, request.note).into(ChangeRequest.newBuilder()).build()
}

@Service
class AgentGrpc(private val auth: Authenticator, private val app: App) : AgentServiceGrpcKt.AgentServiceCoroutineImplBase() {
    private val agents get() = app.agents

    override suspend fun listAgents(request: Empty): AgentConfigs = agents.list(auth.caller()).itemsInto(AgentConfigs.newBuilder()).build()

    override suspend fun listEligibleAgents(request: EligibleAgentsRequest): AgentConfigs {
        Access.requireRead(auth.caller())
        val action = request.action.toDomain<DAgentAction>() ?: throw Invalid("action is required")
        return agents.eligible(action).itemsInto(AgentConfigs.newBuilder()).build()
    }

    override suspend fun getAgent(request: IdRequest): AgentConfig = agents.get(auth.caller(), request.id).into(AgentConfig.newBuilder()).build()

    override suspend fun createAgent(request: AgentInput): AgentCredentials =
        agents.create(auth.caller(), request.decode()).into(AgentCredentials.newBuilder()).build()

    override suspend fun updateAgent(request: UpdateAgentRequest): AgentConfig =
        agents.update(auth.caller(), request.id, request.agent.decode()).into(AgentConfig.newBuilder()).build()

    override suspend fun deleteAgent(request: IdRequest): Empty = EMPTY.also { agents.delete(auth.caller(), request.id) }

    override suspend fun rotateAgentCredentials(request: IdRequest): AgentCredentials =
        agents.rotateCredentials(auth.caller(), request.id).into(AgentCredentials.newBuilder()).build()

    override suspend fun getLlm(request: Empty): LlmView = agents.llm(auth.caller()).into(LlmView.newBuilder()).build()

    override suspend fun updateLlm(request: LlmInput): LlmView {
        val input = request.decode<mayday.agents.LlmInput>().copy(
            headers = request.headersMap.takeIf { request.replaceHeaders },
            tokenParams = request.tokenParamsMap.takeIf { request.replaceTokenParams },
        )
        return agents.updateLlm(auth.caller(), input).into(LlmView.newBuilder()).build()
    }

    override suspend fun listMcpServers(request: Empty): McpServerViews = agents.mcpServers(auth.caller()).itemsInto(McpServerViews.newBuilder()).build()

    override suspend fun saveMcpServer(request: SaveMcpServerRequest): McpServerView =
        agents.saveMcpServer(auth.caller(), request.id.orNull(), request.decode()).into(McpServerView.newBuilder()).build()

    override suspend fun deleteMcpServer(request: IdRequest): Empty = EMPTY.also { agents.deleteMcpServer(auth.caller(), request.id) }
}

@Service
class AgentRunGrpc(private val auth: Authenticator, private val app: App) : AgentRunServiceGrpcKt.AgentRunServiceCoroutineImplBase() {
    private val agents get() = app.agents

    override suspend fun listRuns(request: ListRunsRequest): AgentRuns {
        val p = auth.caller()
        val runs = when (request.subjectKind.toDomain<DSubjectKind>()) {
            DSubjectKind.INCIDENT -> agents.runsFor(p, app.incidents.get(p, request.subjectRef).id)
            DSubjectKind.ALERT -> agents.runsFor(p, request.subjectRef)
            null -> agents.recentRuns(p, request.limit.orDefault(50))
        }
        return runs.itemsInto(AgentRuns.newBuilder()).build()
    }

    override suspend fun getRun(request: IdRequest): AgentRun = agents.run(auth.caller(), request.id).into(AgentRun.newBuilder()).build()

    override suspend fun startRun(request: StartRunRequest): AgentRun {
        val action = request.action.toDomain<DAgentAction>() ?: throw Invalid("action is required")
        val kind = request.subjectKind.toDomain<DSubjectKind>() ?: throw Invalid("subject_kind is required")
        val agentId = if (request.hasAgentId()) request.agentId else null
        return agents.trigger(auth.caller(), action, kind, request.subjectRef, agentId, request.prompt).into(AgentRun.newBuilder()).build()
    }

    override suspend fun cancelRun(request: IdRequest): AgentRun = agents.cancel(auth.caller(), request.id).into(AgentRun.newBuilder()).build()

    override suspend fun claimRuns(request: ClaimRunsRequest): ClaimedRuns =
        agents.claim(auth.caller(), request.limit.orDefault(5)).itemsInto(ClaimedRuns.newBuilder()).build()

    override suspend fun reportProgress(request: RunProgressRequest): AgentRun =
        agents.progress(auth.caller(), request.id, request.text).into(AgentRun.newBuilder()).build()

    override suspend fun completeRun(request: CompleteRunRequest): AgentRun =
        agents.complete(auth.caller(), request.id, request.success, request.output, request.error).into(AgentRun.newBuilder()).build()
}

/** Live domain events. Each subscriber has a bounded buffer; a slow subscriber loses the oldest events. */
@Service
class EventGrpc(
    private val auth: Authenticator,
    private val app: App,
    props: MaydayProperties,
    registry: MeterRegistry,
) : EventServiceGrpcKt.EventServiceCoroutineImplBase() {
    private val clients = AtomicInteger()
    private val max = props.maxLiveConnections

    init {
        Gauge.builder("mayday.live.connections", clients) { it.get().toDouble() }.register(registry)
    }

    override fun subscribe(request: SubscribeRequest): Flow<Event> = flow {
        Access.requireRead(auth.caller())
        if (clients.incrementAndGet() > max) {
            clients.decrementAndGet()
            throw Unavailable("Too many live connections")
        }
        val live = Channel<DomainEvent>(256, BufferOverflow.DROP_OLDEST)
        val sub = app.ports.events.subscribe { live.trySend(it) }
        try {
            emit(ping())
            emitAll(merge(live.receiveAsFlow().map { it.into(Event.newBuilder()).build() }, heartbeat()))
        } finally {
            sub.close()
            clients.decrementAndGet()
        }
    }

    private fun heartbeat(): Flow<Event> = flow {
        while (true) {
            delay(25_000)
            emit(ping())
        }
    }

    private fun ping() = DomainEvent("ping", "", Instant.now()).into(Event.newBuilder()).build()
}
