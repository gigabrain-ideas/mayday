package mayday.contract

import com.google.protobuf.Empty
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import mayday.adapters.grpc.Grpc
import mayday.agents.BudgetExhausted
import mayday.api.v1.*
import mayday.eventually
import mayday.policy.Keys
import mayday.suspendTest
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mayday.identity.TokenScope as DTokenScope

/** Native gRPC contract: status codes, trailers and payloads that clients and agents rely on. */
@TestPropertySource(properties = ["spring.grpc.server.inprocess.name=grpc-contract", "mayday.trace-url-template=https://traces.example.com/trace/{traceId}"])
class GrpcContractTest : ServerContract() {
    private val empty = Empty.getDefaultInstance()
    private val authn by lazy { AuthServiceGrpcKt.AuthServiceCoroutineStub(channel) }
    private val users by lazy { UserServiceGrpcKt.UserServiceCoroutineStub(channel) }
    private val directory by lazy { DirectoryServiceGrpcKt.DirectoryServiceCoroutineStub(channel) }
    private val incidents by lazy { IncidentServiceGrpcKt.IncidentServiceCoroutineStub(channel) }
    private val alerts by lazy { AlertServiceGrpcKt.AlertServiceCoroutineStub(channel) }
    private val policy by lazy { PolicyServiceGrpcKt.PolicyServiceCoroutineStub(channel) }
    private val agents by lazy { AgentServiceGrpcKt.AgentServiceCoroutineStub(channel) }
    private val runs by lazy { AgentRunServiceGrpcKt.AgentRunServiceCoroutineStub(channel) }
    private val reports by lazy { ReportServiceGrpcKt.ReportServiceCoroutineStub(channel) }
    private val events by lazy { EventServiceGrpcKt.EventServiceCoroutineStub(channel) }
    private val oncall by lazy { OnCallServiceGrpcKt.OnCallServiceCoroutineStub(channel) }
    private val escalation by lazy { EscalationServiceGrpcKt.EscalationServiceCoroutineStub(channel) }

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun declare(title: String, sev: Severity = Severity.SEVERITY_SEV3, labels: Map<String, String> = emptyMap()) =
        DeclareIncidentRequest.newBuilder().setTitle(title).setSeverity(sev).addTeamIds("checkout").putAllLabels(labels).build()

    private fun patch(ref: String, f: IncidentPatch.Builder.() -> Unit) =
        UpdateIncidentRequest.newBuilder().setRef(ref).setPatch(IncidentPatch.newBuilder().apply(f)).build()

    private fun ref(key: String) = IncidentRef.newBuilder().setRef(key).build()
    private fun id(id: String) = IdRequest.newBuilder().setId(id).build()

    // ---- auth ----

    @Test
    fun `calls without credentials are unauthenticated except the auth config`() = suspendTest {
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { incidents.listIncidents(ListIncidentsRequest.getDefaultInstance()) }
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { incidents.listIncidents(ListIncidentsRequest.getDefaultInstance(), bearer("mdy_nope")) }
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") {
            incidents.listIncidents(ListIncidentsRequest.getDefaultInstance(), Metadata().apply { put(Grpc.COOKIE, "mayday_session=forged") })
        }
        assertEquals("https://traces.example.com/trace/{traceId}", authn.getMe(empty, auth("viewer")).traceUrlTemplate)
        val config = authn.getAuthConfig(empty)
        assertTrue(config.devLogin)
        assertFalse(config.sso)
    }

    @Test
    fun `bearer API tokens work over native gRPC`() = suspendTest {
        val me = authn.getMe(empty, auth("member"))
        assertEquals("member@example.com", me.user.email)
        assertEquals(OrgRole.ORG_ROLE_MEMBER, me.user.orgRole)
        assertEquals("USER", me.actorKind)
        val who = authn.whoAmI(empty, auth("admin"))
        assertEquals("ADMIN", who.role)
        assertEquals("USER", who.kind)
    }

    @Test
    fun `session cookies work over native gRPC too`() = suspendTest {
        val cookie = login("viewer@example.com")
        val me = authn.getMe(empty, Metadata().apply { put(Grpc.COOKIE, cookie) })
        assertEquals(OrgRole.ORG_ROLE_VIEWER, me.user.orgRole)
    }

    // ---- error mapping ----

    @Test
    fun `domain errors map to gRPC status codes with the domain code in a trailer`() = suspendTest {
        assertStatus(Status.Code.NOT_FOUND, "not_found") { incidents.getIncident(ref("INC-99999"), auth("member")) }
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { incidents.declareIncident(declare("Nope"), auth("viewer")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { incidents.declareIncident(declare(" "), auth("member")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            incidents.declareIncident(DeclareIncidentRequest.newBuilder().setTitle("No severity").addTeamIds("checkout").build(), auth("member"))
        }
        val i = incidents.declareIncident(declare("Versioned ${unique()}"), auth("member"))
        incidents.updateIncident(patch(i.key) { title = "v2" }, auth("member"))
        val e = assertStatus(Status.Code.ABORTED, "conflict") { incidents.updateIncident(patch(i.key) { title = "stale"; version = i.version }, auth("member")) }
        assertNotNull(e.status.description)
    }

    @Test
    fun `a locked setting answers FAILED_PRECONDITION with the locked code`() = suspendTest {
        policy.setSetting(SetSettingRequest.newBuilder().setScope(Scope.SCOPE_ORG).setKey(Keys.POSTMORTEM_DUE_DAYS).setValue("7")
            .setEnforcement(Enforcement.ENFORCEMENT_LOCKED).build(), auth("admin"))
        try {
            assertStatus(Status.Code.FAILED_PRECONDITION, "locked") {
                policy.setSetting(SetSettingRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").setKey(Keys.POSTMORTEM_DUE_DAYS)
                    .setValue("14").build(), auth("lead"))
            }
        } finally {
            policy.clearSetting(ClearSettingRequest.newBuilder().setScope(Scope.SCOPE_ORG).setKey(Keys.POSTMORTEM_DUE_DAYS).build(), auth("admin"))
        }
    }

    // ---- incidents ----

    @Test
    fun `incident list filters and pages with a cursor`() = suspendTest {
        val tag = "Cursor${unique()}"
        val a = incidents.declareIncident(declare("$tag one", Severity.SEVERITY_SEV4), auth("member"))
        val b = incidents.declareIncident(declare("$tag two", Severity.SEVERITY_SEV4), auth("member"))
        val c = incidents.declareIncident(declare("$tag three", Severity.SEVERITY_SEV5), auth("member"))
        incidents.updateIncident(patch(c.key) { status = IncidentStatus.INCIDENT_STATUS_RESOLVED }, auth("member"))

        val q = ListIncidentsRequest.newBuilder().setQ(tag)
        val first = incidents.listIncidents(q.clone().setLimit(2).build(), auth("viewer"))
        assertEquals(listOf(c.key, b.key), first.itemsList.map { it.key })
        assertTrue(first.hasNextCursor())
        val second = incidents.listIncidents(q.clone().setLimit(2).setCursor(first.nextCursor).build(), auth("viewer"))
        assertEquals(listOf(a.key), second.itemsList.map { it.key })
        assertFalse(second.hasNextCursor())

        assertEquals(setOf(a.key, b.key), incidents.listIncidents(q.clone().setOpen(true).build(), auth("viewer")).itemsList.map { it.key }.toSet())
        assertEquals(listOf(c.key), incidents.listIncidents(q.clone().addStatus(IncidentStatus.INCIDENT_STATUS_RESOLVED).build(), auth("viewer")).itemsList.map { it.key })
        assertEquals(listOf(c.key), incidents.listIncidents(q.clone().addSeverity(Severity.SEVERITY_SEV5).build(), auth("viewer")).itemsList.map { it.key })
        assertEquals(3, incidents.listIncidents(q.clone().addTeam("checkout").build(), auth("viewer")).itemsCount)
        assertEquals(0, incidents.listIncidents(q.clone().addTeam("sre").build(), auth("viewer")).itemsCount)
        assertStatus(Status.Code.NOT_FOUND, "not_found") { incidents.listIncidents(q.clone().addTeam("nope").build(), auth("viewer")) }
    }

    @Test
    fun `incident updates follow patch semantics`() = suspendTest {
        val i = incidents.declareIncident(declare("Patch ${unique()}", labels = mapOf("env" to "prod")).toBuilder().setDescription("desc").build(), auth("member"))
        assertEquals(1, i.version)
        assertEquals(1, i.alertIdsCount, "a declared incident raises an alert for its team")

        val renamed = incidents.updateIncident(patch(i.key) { title = "Renamed" }, auth("member"))
        assertEquals("Renamed", renamed.title)
        assertEquals("desc", renamed.description)
        assertEquals(mapOf("env" to "prod"), renamed.labelsMap)
        assertEquals(listOf(team.id), renamed.teamIdsList)
        assertEquals(Severity.SEVERITY_SEV3, renamed.severity)
        assertEquals(2, renamed.version)

        val relabeled = incidents.updateIncident(patch(i.key) { putLabels("region", "eu") }, auth("member"))
        assertEquals(mapOf("region" to "eu"), relabeled.labelsMap)

        val cleared = incidents.updateIncident(patch(i.key) { replaceLabels = true }, auth("member"))
        assertTrue(cleared.labelsMap.isEmpty())

        val moved = incidents.updateIncident(patch(i.key) {
            severity = Severity.SEVERITY_SEV2; status = IncidentStatus.INCIDENT_STATUS_IDENTIFIED; addTeamIds("sre"); description = ""; version = cleared.version
        }, auth("admin"))
        assertEquals(Severity.SEVERITY_SEV2, moved.severity)
        assertEquals(IncidentStatus.INCIDENT_STATUS_IDENTIFIED, moved.status)
        assertEquals(listOf(other.id), moved.teamIdsList)
        assertEquals("", moved.description)
        assertTrue(moved.hasAcknowledgedAt())

        val resolved = incidents.updateIncident(patch(i.key) { status = IncidentStatus.INCIDENT_STATUS_RESOLVED }, auth("admin"))
        assertTrue(resolved.hasResolvedAt() && resolved.hasImpactEndedAt())
        assertTrue(resolved.postmortemRequired, "SEV2 needs a postmortem by default")
        assertTrue(resolved.hasPostmortemDueAt())
        assertEquals(resolved, incidents.getIncident(ref(resolved.number.toString()), auth("viewer")))
    }

    @Test
    fun `timeline, postmortem, resolution and runbook`() = suspendTest {
        val i = incidents.declareIncident(declare("Story ${unique()}"), auth("member"))
        val entry = incidents.addTimelineEntry(AddTimelineEntryRequest.newBuilder().setRef(i.key).setText("Rolled back").build(), auth("member"))
        assertEquals(EntryKind.ENTRY_KIND_NOTE, entry.kind)
        assertEquals(ActorKind.ACTOR_KIND_USER, entry.actor.kind)
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            incidents.addTimelineEntry(AddTimelineEntryRequest.newBuilder().setRef(i.key).setText("hi").build(), auth("viewer"))
        }

        val pm = incidents.savePostmortem(SavePostmortemRequest.newBuilder().setRef(i.key).setPostmortem(
            Postmortem.newBuilder().setSummary("Pool exhausted").setRootCause("Config").addActionItems(ActionItem.newBuilder().setText("Alert on pool"))).build(), auth("member"))
        assertEquals("Pool exhausted", pm.postmortem.summary)
        assertEquals("Alert on pool", pm.postmortem.actionItemsList.single().text)
        assertEquals(PostmortemStatus.POSTMORTEM_STATUS_DRAFT, pm.postmortem.status)
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { incidents.savePostmortem(SavePostmortemRequest.newBuilder().setRef(i.key).build(), auth("member")) }

        val res = incidents.saveResolution(SaveResolutionRequest.newBuilder().setRef(i.key).setNotes("We rolled back.").build(), auth("member"))
        assertEquals("We rolled back.", res.resolutionNotes)
        val rb = incidents.saveRunbook(SaveRunbookRequest.newBuilder().setRef(i.key).setTitle("Restore").setBody("1. Restart").build(), auth("member"))
        assertEquals("Restore", rb.runbook.title)
        assertEquals(ActorKind.ACTOR_KIND_USER, rb.runbook.generatedBy.kind)

        val kinds = incidents.listTimeline(ref(i.key), auth("viewer")).itemsList.map { it.kind }
        assertTrue(kinds.containsAll(listOf(EntryKind.ENTRY_KIND_NOTE, EntryKind.ENTRY_KIND_POSTMORTEM, EntryKind.ENTRY_KIND_RESOLUTION, EntryKind.ENTRY_KIND_RUNBOOK)), kinds.toString())
    }

    // ---- alerts ----

    @Test
    fun `alerts deduplicate, acknowledge, resolve and link`() = suspendTest {
        val fp = "fp-${unique()}"
        val signal = AlertSignal.newBuilder().setTitle("Disk 80%").setSeverity(Severity.SEVERITY_SEV4).setTeam("checkout").setFingerprint(fp).putLabels("host", "db1").build()
        val first = alerts.ingestAlert(signal, auth("member"))
        assertTrue(first.created)
        assertFalse(first.hasIncidentKey())
        assertEquals("api", first.alert.source)
        val second = alerts.ingestAlert(signal, auth("member"))
        assertFalse(second.created)
        assertEquals(first.alert.id, second.alert.id)
        assertEquals(2, second.alert.count)

        val acked = alerts.acknowledgeAlert(id(first.alert.id), auth("member"))
        assertEquals(AlertStatus.ALERT_STATUS_ACKNOWLEDGED, acked.status)
        assertEquals(userOf("member").id, acked.acknowledgedBy.id)

        val i = incidents.declareIncident(declare("Linked ${unique()}"), auth("member"))
        val linked = alerts.linkAlert(LinkAlertRequest.newBuilder().setId(first.alert.id).setIncident(i.key).build(), auth("member"))
        assertEquals(i.id, linked.incidentId)
        assertTrue(first.alert.id in incidents.getIncident(ref(i.key), auth("viewer")).alertIdsList)

        val updated = alerts.addAlertUpdate(AlertTextRequest.newBuilder().setId(first.alert.id).setText("Cleaning up").build(), auth("member"))
        assertTrue(updated.updatesList.any { it.text == "Cleaning up" })
        val resolved = alerts.resolveAlert(AlertTextRequest.newBuilder().setId(first.alert.id).setText("Freed space").build(), auth("member"))
        assertEquals(AlertStatus.ALERT_STATUS_RESOLVED, resolved.status)
        assertTrue(resolved.hasResolvedAt())

        val listed = alerts.listAlerts(ListAlertsRequest.newBuilder().setQ("Disk 80%").addStatus(AlertStatus.ALERT_STATUS_RESOLVED).build(), auth("viewer"))
        assertTrue(listed.itemsList.any { it.id == first.alert.id })
        assertEquals(resolved, alerts.getAlert(id(first.alert.id), auth("viewer")))
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { alerts.acknowledgeAlert(id(first.alert.id), auth("viewer")) }
        assertStatus(Status.Code.NOT_FOUND, "not_found") { alerts.getAlert(id("missing"), auth("viewer")) }
    }

    @Test
    fun `the firing count is exact and follows ingest and acknowledge`() = suspendTest {
        val firing = ListAlertsRequest.newBuilder().addStatus(AlertStatus.ALERT_STATUS_FIRING).addTeam("checkout").build()
        val before = alerts.countAlerts(firing, auth("viewer")).count
        val a = alerts.ingestAlert(AlertSignal.newBuilder().setTitle("Queue lag ${unique()}").setSeverity(Severity.SEVERITY_SEV4).setTeam("checkout").build(), auth("member")).alert
        assertEquals(before + 1, alerts.countAlerts(firing, auth("viewer")).count)
        alerts.acknowledgeAlert(id(a.id), auth("member"))
        assertEquals(before, alerts.countAlerts(firing, auth("viewer")).count)
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { alerts.countAlerts(firing) }
        assertStatus(Status.Code.NOT_FOUND, "not_found") { alerts.countAlerts(firing.toBuilder().addTeam("no-such-team").build(), auth("viewer")) }
    }

    @Test
    fun `severe alerts open an incident`() = suspendTest {
        val r = alerts.ingestAlert(AlertSignal.newBuilder().setTitle("DB down ${unique()}").setSeverity(Severity.SEVERITY_SEV1).setTeam("checkout").build(), auth("member"))
        assertTrue(r.hasIncidentKey())
        assertEquals(r.alert.incidentId, incidents.getIncident(ref(r.incidentKey), auth("viewer")).id)
    }

    // ---- policy ----

    @Test
    fun `settings view, set, clear and change requests`() = suspendTest {
        val view = policy.getSettings(GetSettingsRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").build(), auth("viewer"))
        assertTrue(view.schemaList.any { it.key == Keys.POSTMORTEM_DUE_DAYS && it.type == SettingType.SETTING_TYPE_INT })
        assertEquals(view.schemaCount, view.effectiveCount)

        val set = policy.setSetting(SetSettingRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").setKey(Keys.ALERT_DEDUP_MINUTES).setValue("15").build(), auth("lead"))
        assertEquals(team.id, set.scopeId)
        assertEquals(Enforcement.ENFORCEMENT_NONE, set.enforcement)
        val after = policy.getSettings(GetSettingsRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId(team.id).build(), auth("viewer"))
        val eff = after.effectiveList.single { it.key == Keys.ALERT_DEDUP_MINUTES }
        assertEquals("15", eff.value)
        assertEquals(Scope.SCOPE_TEAM, eff.source)
        assertTrue(after.rawList.any { it.key == Keys.ALERT_DEDUP_MINUTES })
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            policy.setSetting(SetSettingRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").setKey(Keys.ALERT_DEDUP_MINUTES).setValue("5").build(), auth("member"))
        }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { policy.setSetting(SetSettingRequest.newBuilder().setKey(Keys.ALERT_DEDUP_MINUTES).setValue("5").build(), auth("admin")) }
        policy.clearSetting(ClearSettingRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").setKey(Keys.ALERT_DEDUP_MINUTES).build(), auth("lead"))
        val cleared = policy.getSettings(GetSettingsRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").build(), auth("viewer"))
        assertTrue(cleared.rawList.none { it.key == Keys.ALERT_DEDUP_MINUTES })

        policy.setSetting(SetSettingRequest.newBuilder().setScope(Scope.SCOPE_ORG).setKey(Keys.AGENT_AUTO_ANALYSIS).setValue("false")
            .setEnforcement(Enforcement.ENFORCEMENT_LOCKED).build(), auth("admin"))
        try {
            val cr = policy.requestChange(RequestChangeRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").setKey(Keys.AGENT_AUTO_ANALYSIS)
                .setValue("true").setReason("Our agent is safe").build(), auth("lead"))
            assertEquals(ChangeRequestStatus.CHANGE_REQUEST_STATUS_PENDING, cr.status)
            val pending = policy.listChangeRequests(ListChangeRequestsRequest.newBuilder().setStatus(ChangeRequestStatus.CHANGE_REQUEST_STATUS_PENDING).build(), auth("viewer"))
            assertTrue(pending.itemsList.any { it.id == cr.id })
            assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { policy.decideChange(DecideChangeRequest.newBuilder().setId(cr.id).setApprove(true).build(), auth("lead")) }
            val decided = policy.decideChange(DecideChangeRequest.newBuilder().setId(cr.id).setApprove(true).setNote("OK").build(), auth("admin"))
            assertEquals(ChangeRequestStatus.CHANGE_REQUEST_STATUS_APPROVED, decided.status)
            assertEquals("OK", decided.decisionNote)
            val teamView = policy.getSettings(GetSettingsRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").build(), auth("viewer"))
            assertEquals("true", teamView.effectiveList.single { it.key == Keys.AGENT_AUTO_ANALYSIS }.value)
        } finally {
            policy.clearSetting(ClearSettingRequest.newBuilder().setScope(Scope.SCOPE_TEAM).setScopeId("checkout").setKey(Keys.AGENT_AUTO_ANALYSIS).build(), auth("admin"))
            policy.clearSetting(ClearSettingRequest.newBuilder().setScope(Scope.SCOPE_ORG).setKey(Keys.AGENT_AUTO_ANALYSIS).build(), auth("admin"))
        }
    }

    // ---- directory, users and tokens ----

    // ---- on-call ----

    private fun ts(i: java.time.Instant) = com.google.protobuf.Timestamp.newBuilder().setSeconds(i.epochSecond).setNanos(i.nano).build()

    private suspend fun newSchedule(): Schedule = oncall.createSchedule(SaveScheduleRequest.newBuilder().setTeamId("checkout").setName("Primary ${unique()}")
        .setTimeZone("Europe/Berlin").setRotationStart(ts(java.time.Instant.now().minus(Duration.ofDays(1)))).setShiftLength(1)
        .setShiftUnit(ShiftUnit.SHIFT_UNIT_WEEKS).addParticipants(userOf("lead").id).addParticipants(userOf("member").id).build(), auth("lead"))

    @Test
    fun `team owners manage schedules and members read who is on call`() = suspendTest {
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            oncall.createSchedule(SaveScheduleRequest.newBuilder().setTeamId("checkout").setName("x").setRotationStart(ts(java.time.Instant.now())).build(), auth("member"))
        }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            oncall.createSchedule(SaveScheduleRequest.newBuilder().setTeamId("checkout").setName("x").setTimeZone("Nowhere/Land").setRotationStart(ts(java.time.Instant.now())).build(), auth("lead"))
        }
        val s = newSchedule()
        assertEquals(ShiftUnit.SHIFT_UNIT_WEEKS, s.shiftUnit)
        assertTrue(oncall.listSchedules(ListSchedulesRequest.newBuilder().setTeamId("checkout").build(), auth("viewer")).itemsList.any { it.id == s.id })
        val now = oncall.getOnCall(GetOnCallRequest.newBuilder().addTeamIds("checkout").build(), auth("viewer")).itemsList.single { it.scheduleId == s.id }
        assertEquals(userOf("lead").id, now.userId)
        assertTrue(now.hasUntil())
        val updated = oncall.updateSchedule(s.toBuilder().let { SaveScheduleRequest.newBuilder().setId(s.id).setName("Renamed").setTimeZone(s.timeZone)
            .setRotationStart(s.rotationStart).setShiftLength(s.shiftLength).setShiftUnit(s.shiftUnit).addAllParticipants(s.participantsList).setVersion(s.version).build() }, auth("lead"))
        assertEquals("Renamed", updated.name)
        assertStatus(Status.Code.ABORTED, "conflict") {
            oncall.updateSchedule(SaveScheduleRequest.newBuilder().setId(s.id).setName("stale").setRotationStart(s.rotationStart).setVersion(s.version).build(), auth("lead"))
        }
        oncall.deleteSchedule(id(s.id), auth("lead"))
        assertStatus(Status.Code.NOT_FOUND, "not_found") { oncall.getSchedule(id(s.id), auth("viewer")) }
    }

    @Test
    fun `members add themselves as override, owners add anyone from the team`() = suspendTest {
        val s = newSchedule()
        val start = java.time.Instant.now()
        fun override(user: String, hours: Long = 48) = CreateOverrideRequest.newBuilder().setScheduleId(s.id).setUserId(userOf(user).id)
            .setStartsAt(ts(start)).setEndsAt(ts(start.plus(Duration.ofHours(hours)))).setReason("Out sick").build()
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { oncall.createOverride(override("lead"), auth("member")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { oncall.createOverride(override("viewer"), auth("lead")) }
        val mine = oncall.createOverride(override("member"), auth("member"))
        val onCall = oncall.getOnCall(GetOnCallRequest.newBuilder().addTeamIds("checkout").build(), auth("viewer")).itemsList.single { it.scheduleId == s.id }
        assertEquals(userOf("member").id, onCall.userId)
        assertEquals(mine.id, onCall.overrideId)
        val window = WindowRequest.newBuilder().setScheduleId(s.id).setFrom(ts(start)).setTo(ts(start.plus(Duration.ofDays(14)))).build()
        val shifts = oncall.listShifts(window, auth("viewer")).itemsList
        assertEquals(mine.id, shifts.first().overrideId)
        assertTrue(shifts.any { !it.hasOverrideId() })
        assertEquals(listOf(mine.id), oncall.listOverrides(window, auth("viewer")).itemsList.map { it.id })
        val byLead = oncall.createOverride(override("member", 1), auth("lead"))
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { oncall.deleteOverride(id(byLead.id), auth("viewer")) }
        oncall.deleteOverride(id(byLead.id), auth("member"))
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            oncall.listShifts(window.toBuilder().setTo(ts(start.plus(Duration.ofDays(100)))).build(), auth("viewer"))
        }
        oncall.deleteSchedule(id(s.id), auth("lead"))
    }

    // ---- escalation ----

    private fun level(minutes: Int, vararg targets: Pair<TargetKind, String>) = EscalationLevel.newBuilder().setDelayMinutes(minutes)
        .addAllTargets(targets.map { (k, v) -> EscalationTarget.newBuilder().setKind(k).setId(v).build() }).build()

    @Test
    fun `team owners manage escalation policies`() = suspendTest {
        val s = newSchedule()
        val req = SaveEscalationPolicyRequest.newBuilder().setTeamId("checkout").setName("Default ${unique()}")
            .addLevels(level(5, TargetKind.TARGET_KIND_SCHEDULE to s.id)).addLevels(level(10, TargetKind.TARGET_KIND_USER to userOf("member").id)).setRepeat(1).build()
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { escalation.createPolicy(req, auth("member")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            escalation.createPolicy(req.toBuilder().clearLevels().addLevels(level(5, TargetKind.TARGET_KIND_SCHEDULE to "missing")).build(), auth("lead"))
        }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { escalation.createPolicy(req.toBuilder().clearLevels().addLevels(level(999, TargetKind.TARGET_KIND_USER to userOf("member").id)).build(), auth("lead")) }
        val p = escalation.createPolicy(req, auth("lead"))
        assertEquals(TargetKind.TARGET_KIND_SCHEDULE, p.levelsList[0].targetsList.single().kind)
        assertEquals(1, p.repeat)
        assertTrue(escalation.listPolicies(ListEscalationPoliciesRequest.newBuilder().setTeamId("checkout").build(), auth("viewer")).itemsList.any { it.id == p.id })
        val updated = escalation.updatePolicy(req.toBuilder().setId(p.id).setName("Renamed").setVersion(p.version).build(), auth("lead"))
        assertEquals("Renamed", updated.name)
        assertStatus(Status.Code.ABORTED, "conflict") { escalation.updatePolicy(req.toBuilder().setId(p.id).setVersion(p.version).build(), auth("lead")) }
        escalation.deletePolicy(id(p.id), auth("lead"))
        assertStatus(Status.Code.NOT_FOUND, "not_found") { escalation.getPolicy(id(p.id), auth("viewer")) }
        oncall.deleteSchedule(id(s.id), auth("lead"))
    }

    @Test
    fun `an agent acknowledgement keeps paging until a person acknowledges`() = suspendTest {
        val s = newSchedule()
        val p = escalation.createPolicy(SaveEscalationPolicyRequest.newBuilder().setTeamId("checkout").setName("Human ${unique()}").setTeamDefault(true)
            .addLevels(level(5, TargetKind.TARGET_KIND_SCHEDULE to s.id)).addLevels(level(10, TargetKind.TARGET_KIND_USER to userOf("member").id)).build(), auth("lead"))
        val creds = agents.createAgent(agentInput("Watcher ${unique()}", Delivery.DELIVERY_PULL, AgentAction.AGENT_ACTION_ANALYZE), auth("admin"))
        val alert = alerts.ingestAlert(AlertSignal.newBuilder().setTitle("Disk ${unique()}").setSeverity(Severity.SEVERITY_SEV2).setTeam("checkout").build(), auth("member")).alert
        val byAgent = alerts.acknowledgeAlert(id(alert.id), bearer(creds.token.secret))
        assertEquals(AlertStatus.ALERT_STATUS_ACKNOWLEDGED, byAgent.status)
        assertEquals(EscalationStatus.ESCALATION_STATUS_ACTIVE, escalation.getEscalation(id(alert.id), auth("viewer")).status)
        assertEquals(2, escalation.escalateNow(id(alert.id), auth("member")).pagesList.size, "responders can still escalate")
        val byPerson = alerts.acknowledgeAlert(id(alert.id), auth("member"))
        assertEquals(userOf("member").id, byPerson.acknowledgedBy.id)
        assertEquals(EscalationStatus.ESCALATION_STATUS_ACKNOWLEDGED, escalation.getEscalation(id(alert.id), auth("viewer")).status)
        escalation.deletePolicy(id(p.id), auth("lead"))
        oncall.deleteSchedule(id(s.id), auth("lead"))
    }

    @Test
    fun `a firing alert pages the default policy and responders escalate now`() = suspendTest {
        val s = newSchedule()
        val p = escalation.createPolicy(SaveEscalationPolicyRequest.newBuilder().setTeamId("checkout").setName("Pager ${unique()}").setTeamDefault(true)
            .addLevels(level(5, TargetKind.TARGET_KIND_SCHEDULE to s.id)).addLevels(level(10, TargetKind.TARGET_KIND_USER to userOf("member").id)).build(), auth("lead"))
        val alert = alerts.ingestAlert(AlertSignal.newBuilder().setTitle("Latency ${unique()}").setSeverity(Severity.SEVERITY_SEV2).setTeam("checkout").build(), auth("member")).alert
        val e = escalation.getEscalation(id(alert.id), auth("viewer"))
        assertEquals(EscalationStatus.ESCALATION_STATUS_ACTIVE, e.status)
        assertEquals(p.id, e.policyId)
        assertEquals(listOf(userOf("lead").id), e.pagesList.single().userIdsList)
        assertTrue(e.hasNextAt())
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { escalation.escalateNow(id(alert.id), auth("viewer")) }
        val next = escalation.escalateNow(id(alert.id), auth("member"))
        assertEquals(2, next.pagesList.last().level)
        assertTrue(next.pagesList.last().manual)
        alerts.acknowledgeAlert(id(alert.id), auth("member"))
        val stopped = escalation.getEscalation(id(alert.id), auth("viewer"))
        assertEquals(EscalationStatus.ESCALATION_STATUS_ACKNOWLEDGED, stopped.status)
        assertFalse(stopped.hasNextAt())
        assertStatus(Status.Code.NOT_FOUND, "not_found") { escalation.getEscalation(id("missing"), auth("viewer")) }
        escalation.deletePolicy(id(p.id), auth("lead"))
        oncall.deleteSchedule(id(s.id), auth("lead"))
    }

    @Test
    fun `team owners manage the members of their team`() = suspendTest {
        val viewer = userOf("viewer").id
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            directory.setTeamMember(SetTeamMemberRequest.newBuilder().setTeamId("checkout").setUserId(viewer).setRole(TeamRole.TEAM_ROLE_MEMBER).build(), auth("member"))
        }
        directory.setTeamMember(SetTeamMemberRequest.newBuilder().setTeamId("checkout").setUserId(viewer).setRole(TeamRole.TEAM_ROLE_MEMBER).build(), auth("lead"))
        val members = directory.listTeamMembers(id("checkout"), auth("viewer")).itemsList
        assertEquals(TeamRole.TEAM_ROLE_MEMBER, members.single { it.userId == viewer }.role)
        assertFalse(members.single { it.userId == viewer }.managed)
        directory.setTeamMember(SetTeamMemberRequest.newBuilder().setTeamId("checkout").setUserId(viewer).build(), auth("lead"))
        assertTrue(directory.listTeamMembers(id("checkout"), auth("viewer")).itemsList.none { it.userId == viewer })
    }

    @Test
    fun `administrators link identity provider groups to a team and the links lock the membership`() = suspendTest {
        val name = "Linked ${unique()}"
        val t = directory.createTeam(SaveTeamRequest.newBuilder().setName(name).setReplaceGroups(true).addMemberGroups("grp-${unique()}").build(), auth("admin"))
        assertEquals(1, t.memberGroupsCount)
        assertStatus(Status.Code.ABORTED, "conflict") {
            directory.setTeamMember(SetTeamMemberRequest.newBuilder().setTeamId(t.id).setUserId(userOf("viewer").id).setRole(TeamRole.TEAM_ROLE_MEMBER).build(), auth("admin"))
        }
        val unlinked = directory.updateTeam(SaveTeamRequest.newBuilder().setId(t.id).setName(name).setReplaceGroups(true).build(), auth("admin"))
        assertEquals(0, unlinked.memberGroupsCount)
        val kept = directory.updateTeam(SaveTeamRequest.newBuilder().setId(t.id).setName(name).build(), auth("admin"))
        assertEquals(0, kept.memberGroupsCount)
        directory.deleteTeam(id(t.id), auth("admin"))
    }

    @Test
    fun `only admins change the directory`() = suspendTest {
        val name = "Team ${unique()}"
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { directory.createTeam(SaveTeamRequest.newBuilder().setName(name).build(), auth("member")) }
        val t = directory.createTeam(SaveTeamRequest.newBuilder().setName(name).setProjectId("").build(), auth("admin"))
        assertFalse(t.hasProjectId())
        assertTrue(directory.listTeams(empty, auth("viewer")).itemsList.any { it.id == t.id })
        val p = directory.createProject(SaveProjectRequest.newBuilder().setName("Proj ${unique()}").build(), auth("admin"))
        val moved = directory.updateTeam(SaveTeamRequest.newBuilder().setId(t.id).setName(name).setProjectId(p.id).build(), auth("admin"))
        assertEquals(p.id, moved.projectId)
        val kept = directory.updateTeam(SaveTeamRequest.newBuilder().setId(t.id).setName("$name 2").build(), auth("admin"))
        assertEquals(p.id, kept.projectId)
        assertTrue(directory.listProjects(empty, auth("viewer")).itemsList.any { it.id == p.id })
        directory.deleteTeam(id(t.id), auth("admin"))
        assertTrue(directory.listTeams(empty, auth("viewer")).itemsList.none { it.id == t.id })
    }

    @Test
    fun `users are created by admins and team memberships change only with replace_teams`() = suspendTest {
        val email = "new-${unique()}@example.com"
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { users.createUser(CreateUserRequest.newBuilder().setEmail(email).build(), auth("member")) }
        val u = users.createUser(CreateUserRequest.newBuilder().setEmail(email).setName("New").build(), auth("admin"))
        assertEquals(OrgRole.ORG_ROLE_MEMBER, u.orgRole)
        assertStatus(Status.Code.ABORTED, "conflict") { users.createUser(CreateUserRequest.newBuilder().setEmail(email).build(), auth("admin")) }

        val joined = users.updateUser(UpdateUserRequest.newBuilder().setId(u.id).putTeams(team.id, TeamRole.TEAM_ROLE_ADMIN).setReplaceTeams(true).build(), auth("admin"))
        assertEquals(mapOf(team.id to TeamRole.TEAM_ROLE_ADMIN), joined.teamsMap)
        val ignored = users.updateUser(UpdateUserRequest.newBuilder().setId(u.id).putTeams(other.id, TeamRole.TEAM_ROLE_MEMBER).setName("Renamed").build(), auth("admin"))
        assertEquals(mapOf(team.id to TeamRole.TEAM_ROLE_ADMIN), ignored.teamsMap, "teams without replace_teams are ignored")
        assertEquals("Renamed", ignored.name)
        val left = users.updateUser(UpdateUserRequest.newBuilder().setId(u.id).setReplaceTeams(true).setRole(OrgRole.ORG_ROLE_VIEWER).build(), auth("admin"))
        assertTrue(left.teamsMap.isEmpty())
        assertEquals(OrgRole.ORG_ROLE_VIEWER, left.orgRole)
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            users.updateUser(UpdateUserRequest.newBuilder().setId(u.id).putTeams("nope", TeamRole.TEAM_ROLE_MEMBER).setReplaceTeams(true).build(), auth("admin"))
        }
        assertStatus(Status.Code.ABORTED, "conflict") {
            users.updateUser(UpdateUserRequest.newBuilder().setId(userOf("admin").id).setRole(OrgRole.ORG_ROLE_MEMBER).build(), auth("admin"))
        }
        val page = users.listUsers(ListUsersRequest.newBuilder().setQ(email).build(), auth("viewer"))
        assertEquals(listOf(u.id), page.itemsList.map { it.id })
    }

    @Test
    fun `token secrets appear only in the issue response and hashes never`() = suspendTest {
        val issued = users.issueToken(IssueTokenRequest.newBuilder().setName("cli").addScopes(TokenScope.TOKEN_SCOPE_READ).addScopes(TokenScope.TOKEN_SCOPE_ADMIN).setTtlDays(3).build(), auth("lead"))
        assertTrue(issued.secret.startsWith("mdy_"))
        assertEquals(listOf(TokenScope.TOKEN_SCOPE_READ), issued.token.scopesList, "non-admins cannot get the admin scope")
        assertTrue(issued.token.hasExpiresAt())
        assertTrue(issued.secret.startsWith(issued.token.prefix))
        assertNull(ApiToken.getDescriptor().findFieldByName("hash"))

        val listed = users.listTokens(empty, auth("lead"))
        val mine = listed.itemsList.single { it.id == issued.token.id }
        assertFalse(listed.toString().contains(issued.secret))
        assertEquals(OwnerKind.OWNER_KIND_USER, mine.ownerKind)

        assertEquals("lead@example.com", authn.getMe(empty, bearer(issued.secret)).user.email)
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { incidents.declareIncident(declare("read only"), bearer(issued.secret)) }
        users.revokeToken(id(issued.token.id), auth("lead"))
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { authn.getMe(empty, bearer(issued.secret)) }
    }

    // ---- agents ----

    private fun agentInput(name: String, delivery: Delivery, vararg actions: AgentAction) = AgentInput.newBuilder().setName(name).setDelivery(delivery)
        .addAllActions(actions.toList()).setRole(OrgRole.ORG_ROLE_MEMBER).setEnabled(true).apply {
            if (delivery == Delivery.DELIVERY_PUSH) endpointUrl = "https://agent.example.com/hook"
        }.build()

    @Test
    fun `creating an agent returns its credentials once`() = suspendTest {
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { agents.createAgent(agentInput("Nope", Delivery.DELIVERY_PULL, AgentAction.AGENT_ACTION_ANALYZE), auth("member")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            agents.createAgent(agentInput("No url", Delivery.DELIVERY_PUSH, AgentAction.AGENT_ACTION_ANALYZE).toBuilder().clearEndpointUrl().build(), auth("admin"))
        }
        val creds = agents.createAgent(agentInput("Pusher ${unique()}", Delivery.DELIVERY_PUSH, AgentAction.AGENT_ACTION_PATCH), auth("admin"))
        assertTrue(creds.token.secret.startsWith("mdy_"))
        assertTrue(creds.hasSigningSecret() && creds.signingSecret.isNotBlank())
        assertEquals("set", creds.agent.secretSealed)
        assertEquals(OwnerKind.OWNER_KIND_AGENT, creds.token.token.ownerKind)

        val read = agents.getAgent(id(creds.agent.id), auth("viewer"))
        assertEquals("set", read.secretSealed)
        assertFalse(read.toString().contains(creds.signingSecret))
        assertTrue(agents.listAgents(empty, auth("viewer")).itemsList.none { it.toString().contains(creds.signingSecret) })

        val eligible = agents.listEligibleAgents(EligibleAgentsRequest.newBuilder().setAction(AgentAction.AGENT_ACTION_PATCH).build(), auth("viewer"))
        assertTrue(eligible.itemsList.any { it.id == creds.agent.id })
        assertTrue(eligible.itemsList.none { it.kind == AgentKind.AGENT_KIND_INTERNAL }, "the built-in agent needs an LLM provider")
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.listEligibleAgents(EligibleAgentsRequest.getDefaultInstance(), auth("viewer")) }

        val who = authn.whoAmI(empty, bearer(creds.token.secret))
        assertEquals("AGENT", who.kind)
        val rotated = agents.rotateAgentCredentials(id(creds.agent.id), auth("admin"))
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { authn.whoAmI(empty, bearer(creds.token.secret)) }
        assertEquals("AGENT", authn.whoAmI(empty, bearer(rotated.token.secret)).kind)
    }

    @Test
    fun `a manual run dispatches a delegated token that acts as the user`() = suspendTest {
        val creds = agents.createAgent(agentInput("Fixer ${unique()}", Delivery.DELIVERY_PUSH, AgentAction.AGENT_ACTION_PATCH), auth("admin"))
        val i = incidents.declareIncident(declare("Run ${unique()}"), auth("member"))
        val run = runs.startRun(StartRunRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key)
            .setAction(AgentAction.AGENT_ACTION_PATCH).setAgentId(creds.agent.id).setPrompt("Open a PR").build(), auth("member"))
        assertEquals(userOf("member").id, run.onBehalfOf)
        assertEquals(RunTrigger.RUN_TRIGGER_MANUAL, run.trigger)
        assertEquals(i.id, run.subjectId)

        eventually { dispatcher.sent.any { it.second.run.id == run.id } }
        val payload = dispatcher.sent.single { it.second.run.id == run.id }.second
        assertTrue(payload.mcpUrl.endsWith("/mcp"))
        val obo = payload.delegatedToken!!
        val who = authn.whoAmI(empty, bearer(obo))
        assertEquals(creds.agent.name, who.actor)
        assertEquals("member@example.com", who.onBehalfOf)
        assertEquals("MEMBER", who.role)
        eventually { runs.getRun(id(run.id), auth("viewer")).status == RunStatus.RUN_STATUS_DISPATCHED }

        val done = runs.completeRun(CompleteRunRequest.newBuilder().setId(run.id).setSuccess(true).setOutput("PR #12").build(), bearer(creds.token.secret))
        assertTrue(run.requiresApproval)
        assertEquals(RunStatus.RUN_STATUS_AWAITING_APPROVAL, done.status)
        assertStatus(Status.Code.UNAUTHENTICATED, null) { authn.whoAmI(empty, bearer(obo)) }
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { runs.approveRun(id(run.id), bearer(creds.token.secret)) }
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { runs.approveRun(id(run.id), auth("viewer")) }
        val approved = runs.approveRun(id(run.id), auth("member"))
        assertEquals(RunStatus.RUN_STATUS_SUCCEEDED, approved.status)
        assertEquals(userOf("member").id, approved.approvedBy.id)
        assertTrue(approved.hasApprovedAt())
        assertStatus(Status.Code.ABORTED, "conflict") { runs.rejectRun(RejectRunRequest.newBuilder().setId(run.id).setReason("late").build(), auth("member")) }
        val listed = runs.listRuns(ListRunsRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key).build(), auth("viewer"))
        assertEquals(listOf(run.id), listed.itemsList.map { it.id })
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            runs.startRun(StartRunRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key)
                .setAction(AgentAction.AGENT_ACTION_PATCH).setAgentId(creds.agent.id).build(), auth("viewer"))
        }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") {
            runs.startRun(StartRunRequest.newBuilder().setSubjectRef(i.key).setAction(AgentAction.AGENT_ACTION_PATCH).build(), auth("member"))
        }
    }

    @Test
    fun `pull agents claim, report and complete runs with their own token`() = suspendTest {
        val creds = agents.createAgent(agentInput("Puller ${unique()}", Delivery.DELIVERY_PULL, AgentAction.AGENT_ACTION_RUNBOOK), auth("admin"))
        val agentAuth = bearer(creds.token.secret)
        val i = incidents.declareIncident(declare("Pull ${unique()}"), auth("member"))
        val run = runs.startRun(StartRunRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key)
            .setAction(AgentAction.AGENT_ACTION_RUNBOOK).setAgentId(creds.agent.id).build(), auth("member"))
        assertEquals(RunStatus.RUN_STATUS_QUEUED, run.status)

        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { runs.claimRuns(ClaimRunsRequest.getDefaultInstance(), auth("member")) }
        val claimed = runs.claimRuns(ClaimRunsRequest.newBuilder().setLimit(5).build(), agentAuth).itemsList.single()
        assertEquals(run.id, claimed.run.id)
        assertEquals(RunStatus.RUN_STATUS_DISPATCHED, claimed.run.status)
        assertTrue(claimed.hasDelegatedToken())
        assertEquals(i.key, claimed.subject.fieldsMap["incident"]?.structValue?.fieldsMap?.get("key")?.stringValue)
        assertTrue(runs.claimRuns(ClaimRunsRequest.getDefaultInstance(), agentAuth).itemsList.isEmpty())

        val running = runs.reportProgress(RunProgressRequest.newBuilder().setId(run.id).setText("Reading logs").build(), agentAuth)
        assertEquals(RunStatus.RUN_STATUS_RUNNING, running.status)
        assertTrue(running.hasStartedAt())
        assertTrue(incidents.listTimeline(ref(i.key), auth("viewer")).itemsList.any { it.text == "Reading logs" && it.actor.kind == ActorKind.ACTOR_KIND_AGENT })

        val done = runs.completeRun(CompleteRunRequest.newBuilder().setId(run.id).setSuccess(false).setError("No access").build(), agentAuth)
        assertEquals(RunStatus.RUN_STATUS_FAILED, done.status)
        assertEquals("No access", done.error)
        assertStatus(Status.Code.ABORTED, "conflict") { runs.completeRun(CompleteRunRequest.newBuilder().setId(run.id).setSuccess(true).build(), agentAuth) }
    }

    // ---- LLM provider, tool gateway and run tracing ----

    private fun llmInput(f: LlmInput.Builder.() -> Unit) = LlmInput.newBuilder().apply(f).build()

    private suspend fun resetLlm() {
        agents.updateLlm(llmInput {
            provider = "anthropic"; model = "claude-opus-5-5"; baseUrl = ""; effort = "high"; enabled = false; apiKey = ""; clientSecret = ""
            credential = CredentialMode.CREDENTIAL_MODE_STATIC; tokenUrl = ""; clientId = ""; assertionFile = ""; tokenFile = ""; scope = ""
            issuer = ""; grant = GrantType.GRANT_TYPE_CLIENT_CREDENTIALS; clientAuth = ClientAuth.CLIENT_AUTH_BASIC
            replaceHeaders = true; replaceTokenParams = true
        }, auth("admin"))
    }

    @Test
    fun `LLM settings round trip every field and never return secrets`() = suspendTest {
        try {
            val saved = agents.updateLlm(llmInput {
                provider = "openai"; model = "gpt-gateway"; baseUrl = "https://ai-gateway.example.com/v1"; effort = "medium"; enabled = false
                authStyle = AuthStyle.AUTH_STYLE_BEARER; credential = CredentialMode.CREDENTIAL_MODE_OAUTH2
                apiKey = "sk-secret-api-key"; clientSecret = "client-secret-value"
                tokenUrl = "https://idp.example.com/oauth2/token"; clientId = "client-1"; assertionFile = "/var/run/secrets/token"
                tokenFile = "/var/run/gateway/token"; scope = "gateway.invoke"; issuer = "https://idp.example.com"
                grant = GrantType.GRANT_TYPE_TOKEN_EXCHANGE; clientAuth = ClientAuth.CLIENT_AUTH_POST
                putTokenParams("audience", "gateway"); replaceTokenParams = true
                putHeaders("x-company-agent-id", "agent-42"); replaceHeaders = true
            }, auth("admin"))
            val read = agents.getLlm(empty, auth("viewer"))
            for (v in listOf(saved, read)) {
                assertEquals("openai", v.provider)
                assertEquals("gpt-gateway", v.model)
                assertEquals("https://ai-gateway.example.com/v1", v.baseUrl)
                assertEquals("medium", v.effort)
                assertFalse(v.enabled)
                assertFalse(v.ready)
                assertEquals(AuthStyle.AUTH_STYLE_BEARER, v.authStyle)
                assertEquals(CredentialMode.CREDENTIAL_MODE_OAUTH2, v.credential)
                assertEquals("https://idp.example.com/oauth2/token", v.tokenUrl)
                assertEquals("https://idp.example.com", v.issuer)
                assertEquals(GrantType.GRANT_TYPE_TOKEN_EXCHANGE, v.grant)
                assertEquals(ClientAuth.CLIENT_AUTH_POST, v.clientAuth)
                assertEquals(mapOf("audience" to "gateway"), v.tokenParamsMap)
                assertEquals("client-1", v.clientId)
                assertEquals("/var/run/secrets/token", v.assertionFile)
                assertEquals("/var/run/gateway/token", v.tokenFile)
                assertEquals("gateway.invoke", v.scope)
                assertEquals(mapOf("x-company-agent-id" to "agent-42"), v.headersMap)
                assertTrue(v.hasApiKey)
                assertTrue(v.hasClientSecret)
                assertTrue(v.managedFieldsList.isEmpty())
                assertFalse(v.hasPausedUntil())
                assertFalse(v.toString().contains("sk-secret-api-key"))
                assertFalse(v.toString().contains("client-secret-value"))
            }
            val cleared = agents.updateLlm(llmInput { apiKey = ""; clientSecret = "" }, auth("admin"))
            assertFalse(cleared.hasApiKey)
            assertFalse(cleared.hasClientSecret)
            assertEquals("gpt-gateway", cleared.model, "unset fields keep their value")
            assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") { agents.updateLlm(llmInput { model = "x" }, auth("member")) }
        } finally {
            resetLlm()
        }
    }

    @Test
    fun `fixed LLM headers change only with replace_headers`() = suspendTest {
        try {
            agents.updateLlm(llmInput { putHeaders("x-team", "sre"); replaceHeaders = true }, auth("admin"))
            val kept = agents.updateLlm(llmInput { model = "claude-opus-5-5"; putHeaders("x-ignored", "1") }, auth("admin"))
            assertEquals(mapOf("x-team" to "sre"), kept.headersMap)
            val replaced = agents.updateLlm(llmInput { putHeaders("x-a", "1"); putHeaders("x-b", "2"); replaceHeaders = true }, auth("admin"))
            assertEquals(mapOf("x-a" to "1", "x-b" to "2"), replaced.headersMap)
            assertTrue(agents.updateLlm(llmInput { replaceHeaders = true }, auth("admin")).headersMap.isEmpty())
        } finally {
            resetLlm()
        }
    }

    @Test
    fun `invalid LLM settings answer INVALID_ARGUMENT and change nothing`() = suspendTest {
        val before = agents.getLlm(empty, auth("admin"))
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { baseUrl = "ftp://gateway.example.com" }, auth("admin")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { baseUrl = "not a url" }, auth("admin")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { tokenUrl = "file:///etc/passwd" }, auth("admin")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { putHeaders("bad header", "1"); replaceHeaders = true }, auth("admin")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { putHeaders("bad:name", "x"); replaceHeaders = true }, auth("admin")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { provider = "gemini" }, auth("admin")) }
        assertStatus(Status.Code.INVALID_ARGUMENT, "invalid") { agents.updateLlm(llmInput { effort = "extreme" }, auth("admin")) }
        assertEquals(before, agents.getLlm(empty, auth("admin")))
    }

    @Test
    fun `the resource_exhausted domain code maps to RESOURCE_EXHAUSTED`() {
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, Grpc.status("resource_exhausted").code)
        assertEquals("resource_exhausted", BudgetExhausted(Duration.ofSeconds(30)).code)
        assertEquals(429, mayday.adapters.http.HttpErrors.statusOf("resource_exhausted").value(), "HTTP edges answer 429")
    }

    @Test
    fun `HTTP 429 from the gateway fails the run, pauses the agent, and saving the settings resumes it`() = suspendTest {
        try {
            agents.updateLlm(llmInput { credential = CredentialMode.CREDENTIAL_MODE_STATIC; apiKey = "sk-test"; enabled = true }, auth("admin"))
            synchronized(llm.script) { llm.script.addLast { throw BudgetExhausted(Duration.ofMinutes(10), "budget of agent-42") } }
            val i = incidents.declareIncident(declare("Budget ${unique()}"), auth("member"))
            val start = StartRunRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key)
                .setAction(AgentAction.AGENT_ACTION_ANALYZE).setAgentId("mayday").build()
            val run = runs.startRun(start, auth("member"))
            eventually { runs.getRun(id(run.id), auth("viewer")).status == RunStatus.RUN_STATUS_FAILED }
            assertTrue(runs.getRun(id(run.id), auth("viewer")).error.contains("paused"))

            val paused = agents.getLlm(empty, auth("viewer"))
            assertTrue(paused.hasPausedUntil())
            assertTrue(paused.pauseReason.isNotBlank())
            val refused = assertStatus(Status.Code.UNAVAILABLE, "unavailable") { runs.startRun(start, auth("member")) }
            assertTrue(refused.status.description!!.contains("budget"), refused.status.description)

            assertFalse(agents.updateLlm(llmInput { effort = "high" }, auth("admin")).hasPausedUntil())
        } finally {
            synchronized(llm.script) { llm.script.clear() }
            resetLlm()
        }
    }

    @Test
    fun `MCP servers round trip agent identity`() = suspendTest {
        val saved = agents.saveMcpServer(SaveMcpServerRequest.newBuilder().setName("Gateway ${unique()}").setUrl("https://tools.example.com/mcp")
            .setHeaderName("Authorization").setEnabled(true).setUseAgentIdentity(true).build(), auth("admin"))
        assertTrue(saved.useAgentIdentity)
        assertFalse(saved.hasCredential)
        val listed = agents.listMcpServers(empty, auth("viewer")).itemsList.single { it.id == saved.id }
        assertTrue(listed.useAgentIdentity)
        val off = agents.saveMcpServer(SaveMcpServerRequest.newBuilder().setId(saved.id).setName(saved.name).setUrl(saved.url).setEnabled(true)
            .setCredential("Bearer static-secret").build(), auth("admin"))
        assertFalse(off.useAgentIdentity)
        assertTrue(off.hasCredential)
        assertFalse(agents.listMcpServers(empty, auth("viewer")).toString().contains("static-secret"))
        agents.deleteMcpServer(id(saved.id), auth("admin"))
    }

    @Test
    fun `runs carry their trace id`() = suspendTest {
        val creds = agents.createAgent(agentInput("Traced ${unique()}", Delivery.DELIVERY_PULL, AgentAction.AGENT_ACTION_ANALYZE), auth("admin"))
        val i = incidents.declareIncident(declare("Trace ${unique()}"), auth("member"))
        val run = runs.startRun(StartRunRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key)
            .setAction(AgentAction.AGENT_ACTION_ANALYZE).setAgentId(creds.agent.id).build(), auth("member"))
        val trace = "4bf92f3577b34da6a3ce929d0e0e4736"
        val stored = app.ports.runs.get(run.id)!!
        app.ports.runs.save(stored.copy(traceId = trace, version = stored.version + 1), stored.version)
        val bySubject = runs.listRuns(ListRunsRequest.newBuilder().setSubjectKind(SubjectKind.SUBJECT_KIND_INCIDENT).setSubjectRef(i.key).build(), auth("viewer"))
        assertEquals(trace, bySubject.itemsList.single().traceId)
        assertEquals(trace, runs.listRuns(ListRunsRequest.newBuilder().setLimit(100).build(), auth("viewer")).itemsList.single { it.id == run.id }.traceId)
    }

    // ---- reports ----

    @Test
    fun `reports count incidents by team`() = suspendTest {
        val before = reports.getReport(ReportRequest.newBuilder().addTeam("sre").build(), auth("viewer"))
        val i = incidents.declareIncident(declare("Report ${unique()}", Severity.SEVERITY_SEV4).toBuilder().clearTeamIds().addTeamIds("sre").build(), auth("admin"))
        incidents.updateIncident(patch(i.key) { status = IncidentStatus.INCIDENT_STATUS_RESOLVED }, auth("admin"))
        val after = reports.getReport(ReportRequest.newBuilder().addTeam("sre").build(), auth("viewer"))
        assertEquals(before.incidents + 1, after.incidents)
        assertEquals(before.resolved + 1, after.resolved)
        assertEquals(before.bySeverityMap.getOrDefault("SEV4", 0) + 1, after.bySeverityMap["SEV4"])
        assertTrue(after.byTeamList.any { it.teamId == other.id })
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { reports.getReport(ReportRequest.getDefaultInstance()) }
    }

    // ---- live events ----

    @Test
    fun `subscribe sends a ping first and then domain events`() = suspendTest {
        val ping = CompletableDeferred<Unit>()
        val viewer = auth("viewer")
        val member = auth("member")
        val received = async {
            withTimeout(10_000) {
                events.subscribe(SubscribeRequest.getDefaultInstance(), viewer)
                    .onEach { if (it.type == "ping") ping.complete(Unit) }
                    .filter { it.type == "incident.created" }
                    .first()
            }
        }
        withTimeout(5_000) { ping.await() }
        val i = incidents.declareIncident(declare("Live ${unique()}"), member)
        val event = received.await()
        assertEquals(i.id, event.subjectId)
        assertEquals(listOf(team.id), event.teamIdsList)
        assertTrue(event.hasAt())
    }

    @Test
    fun `subscribe needs credentials`() = suspendTest {
        assertStatus(Status.Code.UNAUTHENTICATED, "unauthenticated") { events.subscribe(SubscribeRequest.getDefaultInstance()).take(1).toList() }
    }

    @Test
    fun `scopes on the token limit what the user may do`() = suspendTest {
        val readOnly = app.identity.issueToken(principal("member"), "ro", setOf(DTokenScope.READ), null).secret
        assertStatus(Status.Code.PERMISSION_DENIED, "forbidden") {
            alerts.ingestAlert(AlertSignal.newBuilder().setTitle("x").setSeverity(Severity.SEVERITY_SEV4).setTeam("checkout").build(), bearer(readOnly))
        }
        val ingest = app.identity.issueToken(principal("member"), "am", setOf(DTokenScope.ALERTS_INGEST), null).secret
        assertTrue(alerts.ingestAlert(AlertSignal.newBuilder().setTitle("Ingest ${unique()}").setSeverity(Severity.SEVERITY_SEV4).setTeam("checkout").build(), bearer(ingest)).created)
    }
}
