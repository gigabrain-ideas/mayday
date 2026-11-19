package mayday.contract

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.Descriptors.EnumDescriptor
import com.google.protobuf.Message
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import mayday.adapters.grpc.ProtoJson
import mayday.adapters.grpc.decode
import mayday.adapters.grpc.into
import mayday.adapters.grpc.toDomain
import mayday.agents.AgentAction
import mayday.agents.AgentConfig
import mayday.agents.AgentKind
import mayday.agents.AgentRun
import mayday.agents.AgentTrigger
import mayday.agents.ClaimedRun
import mayday.agents.Delivery
import mayday.agents.RunStatus
import mayday.agents.RunTrigger
import mayday.agents.SubjectKind
import mayday.agents.TriggerEvent
import mayday.alerting.Alert
import mayday.alerting.AlertStatus
import mayday.alerting.AlertUpdate
import mayday.api.v1.Event
import mayday.identity.ApiToken
import mayday.identity.OrgRole
import mayday.identity.OwnerKind
import mayday.identity.TeamRole
import mayday.identity.TokenScope
import mayday.identity.User
import mayday.incidents.ActionItem
import mayday.incidents.EntryKind
import mayday.incidents.Incident
import mayday.incidents.IncidentStatus
import mayday.incidents.Postmortem
import mayday.incidents.PostmortemStatus
import mayday.incidents.Runbook
import mayday.incidents.TimelineEntry
import mayday.policy.ChangeRequest
import mayday.policy.ChangeRequestStatus
import mayday.policy.EffectiveSetting
import mayday.policy.Enforcement
import mayday.policy.Scope
import mayday.policy.SettingCatalog
import mayday.policy.SettingDef
import mayday.policy.SettingType
import mayday.policy.SettingValue
import mayday.policy.Strictness
import mayday.reporting.DayRow
import mayday.reporting.IncidentRow
import mayday.reporting.Report
import mayday.reporting.ReportQuery
import mayday.reporting.TeamRow
import mayday.shared.Actor
import mayday.shared.ActorKind
import mayday.shared.DomainEvent
import mayday.shared.Invalid
import mayday.shared.JSON
import mayday.shared.Severity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mayday.api.v1.AgentConfig as PAgentConfig
import mayday.api.v1.AgentRun as PAgentRun
import mayday.api.v1.Alert as PAlert
import mayday.api.v1.ApiToken as PApiToken
import mayday.api.v1.ChangeRequest as PChangeRequest
import mayday.api.v1.ClaimedRun as PClaimedRun
import mayday.api.v1.EffectiveSetting as PEffectiveSetting
import mayday.api.v1.Incident as PIncident
import mayday.api.v1.Report as PReport
import mayday.api.v1.SettingDef as PSettingDef
import mayday.api.v1.SettingValue as PSettingValue
import mayday.api.v1.TimelineEntry as PTimelineEntry
import mayday.api.v1.User as PUser

/** Domain -> proto -> domain must not lose data, and the proto enums must match the domain enums. */
class ProtoJsonContractTest {
    private val t0 = Instant.parse("2026-01-02T03:04:05.123456789Z")
    private val t1 = Instant.parse("2026-01-02T05:00:00Z")
    private val user = Actor(ActorKind.USER, "u1", "Ann")
    private val agent = Actor(ActorKind.AGENT, "a1", "Bot", onBehalfOf = "u1")

    private inline fun <reified T, B : Message.Builder> roundTrip(value: T, builder: B): T {
        val proto = value.into(builder).build()
        return proto.decode<T>()
    }

    private val minimalIncident = Incident("i1", 1, "INC-1", "Down", severity = Severity.SEV3, teamIds = listOf("t1"),
        reportedAt = t0, createdBy = Actor.system, createdAt = t0, updatedAt = t0)

    private val fullIncident = Incident(
        "i2", 42, "OPS-42", "Checkout down", "Errors everywhere", Severity.SEV1, IncidentStatus.MONITORING, listOf("t1", "t2"),
        commanderId = "u1", impactStartedAt = t0, impactEndedAt = t1, discoveredAt = t0, reportedAt = t0, acknowledgedAt = t0,
        resolvedAt = t1, postmortemRequired = true, postmortemDueAt = t1,
        postmortem = Postmortem(PostmortemStatus.IN_REVIEW, "s", "i", "rc", "d", "r", "l", listOf(ActionItem("fix", "ann", true), ActionItem("doc")), agent, t1),
        resolutionNotes = "Rolled back", runbook = Runbook("Restore", "1. Do it", agent, t1), alertIds = listOf("al1"),
        labels = mapOf("env" to "prod", "region" to "eu"), createdBy = user, createdAt = t0, updatedAt = t1, version = 9,
    )

    @Test
    fun `incident with every optional field unset`() {
        assertEquals(minimalIncident, roundTrip(minimalIncident, PIncident.newBuilder()))
        val proto = minimalIncident.into(PIncident.newBuilder()).build()
        assertFalse(proto.hasCommanderId() || proto.hasPostmortem() || proto.hasRunbook() || proto.hasResolvedAt())
    }

    @Test
    fun `incident with every optional field set`() {
        assertEquals(fullIncident, roundTrip(fullIncident, PIncident.newBuilder()))
        val proto = fullIncident.into(PIncident.newBuilder()).build()
        assertEquals(mayday.api.v1.Severity.SEVERITY_SEV1, proto.severity)
        assertEquals(t0.nano, proto.impactStartedAt.nanos)
    }

    @Test
    fun `timeline entry`() {
        val e = TimelineEntry("e1", "i1", t0, EntryKind.RESOLUTION, agent, "Done", mapOf("from" to "SEV2"))
        assertEquals(e, roundTrip(e, PTimelineEntry.newBuilder()))
    }

    @Test
    fun alert() {
        val minimal = Alert("al1", "fp", "api", "Disk", severity = Severity.SEV4, teamId = "t1", firstSeenAt = t0, lastSeenAt = t0)
        val full = minimal.copy(description = "d", status = AlertStatus.ACKNOWLEDGED, labels = mapOf("a" to "b"), externalUrl = "https://x",
            incidentId = "i1", count = 12, acknowledgedBy = user, resolvedAt = t1, updates = listOf(AlertUpdate(t1, user, "looking")), version = 3)
        assertEquals(minimal, roundTrip(minimal, PAlert.newBuilder()))
        assertEquals(full, roundTrip(full, PAlert.newBuilder()))
    }

    private val run = AgentRun("r1", "a1", "Bot", AgentAction.PATCH, SubjectKind.ALERT, "al1", "Disk", RunStatus.RUNNING, RunTrigger.AUTO,
        agent, onBehalfOf = "u1", prompt = "p", output = "o", error = "e", createdAt = t0, startedAt = t0, finishedAt = t1)

    @Test
    fun `agent run drops only the internal version`() {
        assertEquals(run, roundTrip(run, PAgentRun.newBuilder()))
        assertEquals(run, roundTrip(run.copy(version = 5), PAgentRun.newBuilder()))
        val bare = run.copy(onBehalfOf = null, startedAt = null, finishedAt = null, prompt = "", output = "", error = "")
        assertEquals(bare, roundTrip(bare, PAgentRun.newBuilder()))
    }

    @Test
    fun `agent config`() {
        val c = AgentConfig("a1", "Bot", "desc", AgentKind.EXTERNAL, Delivery.PULL, "https://hook", "set", setOf(AgentAction.ANALYZE, AgentAction.RUNBOOK),
            listOf(AgentTrigger(TriggerEvent.ALERT_CREATED, Severity.SEV3, listOf("t1"), AgentAction.RUNBOOK)), OrgRole.MEMBER, false, "be brief", t0, t1)
        assertEquals(c, roundTrip(c, PAgentConfig.newBuilder()))
        val bare = c.copy(endpointUrl = null, secretSealed = "", triggers = emptyList())
        assertEquals(bare, roundTrip(bare, PAgentConfig.newBuilder()))
    }

    @Test
    fun user() {
        val u = User("u1", "a@example.com", "Ann", OrgRole.ADMIN, mapOf("t1" to TeamRole.ADMIN, "t2" to TeamRole.MEMBER), setOf("p1"), "idp|1", true, t0)
        assertEquals(u, roundTrip(u, PUser.newBuilder()))
        val bare = User("u2", "b@example.com", "B", createdAt = t0)
        assertEquals(bare, roundTrip(bare, PUser.newBuilder()))
    }

    @Test
    fun `api token never carries its hash`() {
        val tok = ApiToken("k1", "cli", OwnerKind.AGENT, "a1", "secret-hash", "mdy_ab", setOf(TokenScope.READ, TokenScope.ALERTS_INGEST), t0, t1)
        val proto = tok.into(PApiToken.newBuilder()).build()
        assertNull(PApiToken.getDescriptor().findFieldByName("hash"))
        assertFalse(proto.toString().contains("secret-hash"))
        val back = JSON.decodeFromJsonElement(ApiToken.serializer(), JsonObject(ProtoJson.toJson(proto).jsonObject + ("hash" to JsonPrimitive(""))))
        assertEquals(tok.copy(hash = ""), back)
        val noExpiry = tok.copy(expiresAt = null)
        assertFalse(noExpiry.into(PApiToken.newBuilder()).build().hasExpiresAt())
    }

    @Test
    fun `setting definitions, effective settings and values`() {
        SettingCatalog.defs.forEach { assertEquals(it, roundTrip(it, PSettingDef.newBuilder())) }
        val def = SettingDef("k", "K", "d", SettingType.INT, "3", setOf(Scope.ORG, Scope.TEAM), Strictness.LOWER, 1, 30)
        assertEquals(def, roundTrip(def, PSettingDef.newBuilder()))
        val eff = EffectiveSetting("k", "3", Scope.TEAM, Scope.ORG, Enforcement.MINIMUM, "5")
        assertEquals(eff, roundTrip(eff, PEffectiveSetting.newBuilder()))
        val defaulted = EffectiveSetting("k", "3", null)
        assertEquals(defaulted, roundTrip(defaulted, PEffectiveSetting.newBuilder()))
        val v = SettingValue(Scope.PROJECT, "p1", "k", "4", Enforcement.LOCKED, true, user, t0)
        assertEquals(v, roundTrip(v, PSettingValue.newBuilder()))
        val none = v.copy(enforcement = Enforcement.NONE, exception = false)
        assertEquals(none, roundTrip(none, PSettingValue.newBuilder()))
    }

    @Test
    fun `change request`() {
        val open = ChangeRequest("c1", Scope.TEAM, "t1", "k", "true", "why", requestedBy = user, createdAt = t0)
        assertEquals(open, roundTrip(open, PChangeRequest.newBuilder()))
        val decided = open.copy(status = ChangeRequestStatus.REJECTED, decidedBy = agent, decidedAt = t1, decisionNote = "no")
        assertEquals(decided, roundTrip(decided, PChangeRequest.newBuilder()))
    }

    @Test
    fun `report drops only the query echo`() {
        val query = ReportQuery(listOf("t1"), t0, t1, listOf(Severity.SEV1))
        val r = Report(query, 5, 2, 3, 10, 4, mapOf("SEV1" to 2, "SEV3" to 3), 1.5, 30.0, null, 2, 1, 1,
            listOf(TeamRow("t1", "Checkout", 5, 2, 12.25, 10), TeamRow("t2", "SRE", 0, 0, null, 0)),
            listOf(DayRow("2026-01-02", mapOf("SEV1" to 1), 3)),
            listOf(IncidentRow("INC-1", "Down", Severity.SEV1, "RESOLVED", 90.0, listOf("t1")), IncidentRow("INC-2", "Slow", Severity.SEV3, "INVESTIGATING", null, emptyList())))
        val json = ProtoJson.toJson(r.into(PReport.newBuilder()).build()).jsonObject
        assertFalse("query" in json)
        val back = JSON.decodeFromJsonElement(Report.serializer(), JsonObject(json + ("query" to JSON.encodeToJsonElement(ReportQuery.serializer(), query))))
        assertEquals(r, back)
    }

    @Test
    fun `claimed run keeps the subject as a JSON object`() {
        val subject = buildJsonObject {
            put("key", "INC-1"); put("count", 3); put("ratio", 0.25); put("open", true); put("none", JsonNull)
            put("teams", JsonArray(listOf(JsonPrimitive("t1"), JsonPrimitive(2))))
            put("nested", buildJsonObject { put("a", "b") })
        }
        val c = ClaimedRun(run, "delegated", subject)
        assertEquals(c, roundTrip(c, PClaimedRun.newBuilder()))
        val noToken = c.copy(delegatedToken = null, subject = JsonObject(emptyMap()))
        assertEquals(noToken, roundTrip(noToken, PClaimedRun.newBuilder()))
    }

    @Test
    fun `domain event`() {
        val e = DomainEvent("incident.updated", "i1", t0, listOf("t1", "t2"), mapOf("key" to "INC-1"))
        assertEquals(e, roundTrip(e, Event.newBuilder()))
        val ping = DomainEvent("ping", "", t0)
        assertEquals(ping, roundTrip(ping, Event.newBuilder()))
    }

    @Test
    fun `missing required input and unknown enum values are invalid`() {
        assertFailsWith<Invalid> { mayday.api.v1.DeclareIncidentRequest.getDefaultInstance().decode<mayday.incidents.NewIncident>() }
        assertFailsWith<Invalid> { mayday.api.v1.Severity.SEVERITY_SEV1.toDomain<IncidentStatus>() }
        assertNull(mayday.api.v1.Severity.SEVERITY_UNSPECIFIED.toDomain<Severity>())
        assertEquals(Severity.SEV2, mayday.api.v1.Severity.SEVERITY_SEV2.toDomain<Severity>())
    }

    private val enums: List<Pair<Array<out Enum<*>>, EnumDescriptor>> = listOf(
        Severity.entries.toTypedArray() to mayday.api.v1.Severity.getDescriptor(),
        ActorKind.entries.toTypedArray() to mayday.api.v1.ActorKind.getDescriptor(),
        IncidentStatus.entries.toTypedArray() to mayday.api.v1.IncidentStatus.getDescriptor(),
        PostmortemStatus.entries.toTypedArray() to mayday.api.v1.PostmortemStatus.getDescriptor(),
        EntryKind.entries.toTypedArray() to mayday.api.v1.EntryKind.getDescriptor(),
        AlertStatus.entries.toTypedArray() to mayday.api.v1.AlertStatus.getDescriptor(),
        OrgRole.entries.toTypedArray() to mayday.api.v1.OrgRole.getDescriptor(),
        TeamRole.entries.toTypedArray() to mayday.api.v1.TeamRole.getDescriptor(),
        TokenScope.entries.toTypedArray() to mayday.api.v1.TokenScope.getDescriptor(),
        OwnerKind.entries.toTypedArray() to mayday.api.v1.OwnerKind.getDescriptor(),
        AgentKind.entries.toTypedArray() to mayday.api.v1.AgentKind.getDescriptor(),
        Delivery.entries.toTypedArray() to mayday.api.v1.Delivery.getDescriptor(),
        AgentAction.entries.toTypedArray() to mayday.api.v1.AgentAction.getDescriptor(),
        TriggerEvent.entries.toTypedArray() to mayday.api.v1.TriggerEvent.getDescriptor(),
        RunStatus.entries.toTypedArray() to mayday.api.v1.RunStatus.getDescriptor(),
        RunTrigger.entries.toTypedArray() to mayday.api.v1.RunTrigger.getDescriptor(),
        SubjectKind.entries.toTypedArray() to mayday.api.v1.SubjectKind.getDescriptor(),
        Scope.entries.toTypedArray() to mayday.api.v1.Scope.getDescriptor(),
        Enforcement.entries.toTypedArray() to mayday.api.v1.Enforcement.getDescriptor(),
        SettingType.entries.toTypedArray() to mayday.api.v1.SettingType.getDescriptor(),
        Strictness.entries.toTypedArray() to mayday.api.v1.Strictness.getDescriptor(),
        ChangeRequestStatus.entries.toTypedArray() to mayday.api.v1.ChangeRequestStatus.getDescriptor(),
    )

    @Test
    fun `every domain enum value has a proto value and back`() {
        for ((values, descriptor) in enums) {
            val prefix = ProtoJson.prefix(descriptor)
            assertEquals(prefix + "UNSPECIFIED", descriptor.findValueByNumber(0).name, descriptor.fullName)
            val protoNames = descriptor.values.filter { it.number != 0 }.map { it.name.removePrefix(prefix) }.toSet()
            assertEquals(values.map { it.name }.toSet(), protoNames, descriptor.fullName)
        }
    }

    private val messages: List<Pair<KSerializer<*>, Descriptor>> = listOf(
        serializer<Incident>() to PIncident.getDescriptor(),
        serializer<Postmortem>() to mayday.api.v1.Postmortem.getDescriptor(),
        serializer<Runbook>() to mayday.api.v1.Runbook.getDescriptor(),
        serializer<TimelineEntry>() to PTimelineEntry.getDescriptor(),
        serializer<Alert>() to PAlert.getDescriptor(),
        serializer<AgentRun>() to PAgentRun.getDescriptor(),
        serializer<AgentConfig>() to PAgentConfig.getDescriptor(),
        serializer<AgentTrigger>() to mayday.api.v1.AgentTrigger.getDescriptor(),
        serializer<User>() to PUser.getDescriptor(),
        serializer<ApiToken>() to PApiToken.getDescriptor(),
        serializer<SettingDef>() to PSettingDef.getDescriptor(),
        serializer<EffectiveSetting>() to PEffectiveSetting.getDescriptor(),
        serializer<SettingValue>() to PSettingValue.getDescriptor(),
        serializer<ChangeRequest>() to PChangeRequest.getDescriptor(),
        serializer<Report>() to PReport.getDescriptor(),
        serializer<ClaimedRun>() to PClaimedRun.getDescriptor(),
        serializer<DomainEvent>() to Event.getDescriptor(),
        serializer<Actor>() to mayday.api.v1.Actor.getDescriptor(),
        serializer<mayday.incidents.NewIncident>() to mayday.api.v1.DeclareIncidentRequest.getDescriptor(),
        serializer<mayday.alerting.AlertSignal>() to mayday.api.v1.AlertSignal.getDescriptor(),
        serializer<mayday.agents.AgentInput>() to mayday.api.v1.AgentInput.getDescriptor(),
    )

    @Test
    fun `every proto field has a domain property with the same JSON name`() {
        for ((ser, descriptor) in messages) {
            val names = (0 until ser.descriptor.elementsCount).map { ser.descriptor.getElementName(it) }.toSet()
            val missing = descriptor.fields.map { it.jsonName }.filter { it !in names }
            assertTrue(missing.isEmpty(), "${descriptor.fullName} has fields without a domain property: $missing")
        }
    }
}
