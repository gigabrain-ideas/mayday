package mayday

import kotlinx.coroutines.delay

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mayday.agents.AgentAction
import mayday.agents.AgentInput
import mayday.agents.AgentTrigger
import mayday.agents.Delivery
import mayday.agents.RunStatus
import mayday.agents.SubjectKind
import mayday.alerting.AlertSignal
import mayday.alerting.AlertStatus
import mayday.identity.OrgRole
import mayday.identity.TokenScope
import mayday.incidents.EntryKind
import mayday.incidents.IncidentPatch
import mayday.incidents.IncidentStatus
import mayday.incidents.NewIncident
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.policy.SettingLocked
import mayday.shared.ActorKind
import mayday.shared.Forbidden
import mayday.shared.Invalid
import mayday.shared.Severity
import mayday.shared.Unauthenticated
import mayday.shared.Unavailable
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private val f = Fixture()
    private val policy = f.app.policy

    @Test
    fun `defaults apply when nothing is set`() = suspendTest {
        assertEquals("SEV2", policy.effective(listOf(f.team.id))[Keys.POSTMORTEM_MIN_SEVERITY])
    }

    @Test
    fun `team admins override within the admin minimum and cannot go below it`() = suspendTest {
        policy.set(f.admin, Scope.ORG, "org", Keys.POSTMORTEM_MIN_SEVERITY, "SEV2", Enforcement.MINIMUM)
        policy.set(f.lead, Scope.TEAM, f.team.id, Keys.POSTMORTEM_MIN_SEVERITY, "SEV4", Enforcement.NONE)
        assertEquals("SEV4", policy.effective(listOf(f.team.id))[Keys.POSTMORTEM_MIN_SEVERITY])
        assertFailsWith<SettingLocked> { policy.set(f.lead, Scope.TEAM, f.team.id, Keys.POSTMORTEM_MIN_SEVERITY, "SEV1", Enforcement.NONE) }
        assertFailsWith<SettingLocked> { policy.set(f.lead, Scope.TEAM, f.team.id, Keys.POSTMORTEM_MIN_SEVERITY, "NONE", Enforcement.NONE) }
    }

    @Test
    fun `locked settings cannot change and a change request grants an exception`() = suspendTest {
        policy.set(f.admin, Scope.ORG, "org", Keys.AGENT_AUTO_ANALYSIS, "false", Enforcement.LOCKED)
        assertFailsWith<SettingLocked> { policy.set(f.lead, Scope.TEAM, f.team.id, Keys.AGENT_AUTO_ANALYSIS, "true", Enforcement.NONE) }
        val cr = policy.requestChange(f.lead, Scope.TEAM, f.team.id, Keys.AGENT_AUTO_ANALYSIS, "true", "Our agent is safe")
        assertFailsWith<Forbidden> { policy.decide(f.lead, cr.id, true, "") }
        policy.decide(f.admin, cr.id, true, "OK")
        assertEquals("true", policy.effective(listOf(f.team.id))[Keys.AGENT_AUTO_ANALYSIS])
        assertEquals("false", policy.effective(listOf(f.other.id))[Keys.AGENT_AUTO_ANALYSIS])
    }

    @Test
    fun `only admins enforce and members cannot change team settings`() = suspendTest {
        assertFailsWith<Forbidden> { policy.set(f.lead, Scope.TEAM, f.team.id, Keys.POSTMORTEM_DUE_DAYS, "5", Enforcement.LOCKED) }
        assertFailsWith<Forbidden> { policy.set(f.member, Scope.TEAM, f.team.id, Keys.POSTMORTEM_DUE_DAYS, "5", Enforcement.NONE) }
        assertFailsWith<Forbidden> { policy.set(f.lead, Scope.TEAM, f.other.id, Keys.POSTMORTEM_DUE_DAYS, "5", Enforcement.NONE) }
        assertFailsWith<Invalid> { policy.set(f.admin, Scope.TEAM, f.team.id, Keys.POSTMORTEM_DUE_DAYS, "500", Enforcement.NONE) }
        assertFailsWith<Invalid> { policy.set(f.admin, Scope.TEAM, f.team.id, Keys.INCIDENT_PREFIX, "X", Enforcement.NONE) }
    }

    @Test
    fun `multi-team values resolve to the strictest`() = suspendTest {
        policy.set(f.admin, Scope.TEAM, f.team.id, Keys.POSTMORTEM_MIN_SEVERITY, "SEV4", Enforcement.NONE)
        policy.set(f.admin, Scope.TEAM, f.other.id, Keys.POSTMORTEM_MIN_SEVERITY, "SEV1", Enforcement.NONE)
        assertEquals("SEV4", policy.effective(listOf(f.team.id, f.other.id))[Keys.POSTMORTEM_MIN_SEVERITY])
    }

    @Test
    fun `project settings apply to their teams`() = suspendTest {
        policy.set(f.admin, Scope.PROJECT, f.project.id, Keys.POSTMORTEM_DUE_DAYS, "3", Enforcement.NONE)
        assertEquals("3", policy.effective(listOf(f.team.id))[Keys.POSTMORTEM_DUE_DAYS])
        assertEquals("7", policy.effective(listOf(f.other.id))[Keys.POSTMORTEM_DUE_DAYS])
    }
}

class IncidentTest {
    private val f = Fixture()
    private val svc = f.app.incidents

    @Test
    fun `incidents get monotonic keys with the configured prefix`() = suspendTest {
        val a = svc.declare(f.member, NewIncident("A", severity = Severity.SEV3, teamIds = listOf("checkout")))
        f.app.policy.set(f.admin, Scope.ORG, "org", Keys.INCIDENT_PREFIX, "OPS", Enforcement.NONE)
        val b = svc.declare(f.member, NewIncident("B", severity = Severity.SEV3, teamIds = listOf(f.team.id)))
        assertEquals("INC-1", a.key)
        assertEquals("OPS-2", b.key)
        assertEquals(b.id, svc.get(f.viewer, "OPS-2").id)
        assertEquals(b.id, svc.get(f.viewer, "2").id)
    }

    @Test
    fun `postmortem is mandatory by policy and gets a due date on resolution`() = suspendTest {
        val i = svc.declare(f.member, NewIncident("Down", severity = Severity.SEV1, teamIds = listOf(f.team.id)))
        assertTrue(i.postmortemRequired)
        assertFailsWith<Invalid> { svc.update(f.member, i.key, IncidentPatch(postmortemRequired = false)) }
        val r = svc.update(f.member, i.key, IncidentPatch(status = IncidentStatus.RESOLVED))
        assertNotNull(r.resolvedAt)
        assertNotNull(r.impactEndedAt)
        assertEquals(Duration.ofDays(7), Duration.between(r.resolvedAt, r.postmortemDueAt))
        val low = svc.declare(f.member, NewIncident("Slow", severity = Severity.SEV4, teamIds = listOf(f.team.id)))
        assertFalse(low.postmortemRequired)
        assertTrue(svc.update(f.member, low.key, IncidentPatch(postmortemRequired = true)).postmortemRequired)
    }

    @Test
    fun `reopening clears the resolution time`() = suspendTest {
        val i = svc.declare(f.member, NewIncident("X", severity = Severity.SEV3, teamIds = listOf(f.team.id)))
        svc.update(f.member, i.key, IncidentPatch(status = IncidentStatus.RESOLVED))
        val reopened = svc.update(f.member, i.key, IncidentPatch(status = IncidentStatus.INVESTIGATING))
        assertNull(reopened.resolvedAt)
    }

    @Test
    fun `every change is on the timeline`() = suspendTest {
        val i = svc.declare(f.member, NewIncident("X", severity = Severity.SEV3, teamIds = listOf(f.team.id)))
        svc.update(f.member, i.key, IncidentPatch(severity = Severity.SEV2, status = IncidentStatus.IDENTIFIED, title = "Y"))
        svc.addEntry(f.member, i.key, "Rolled back")
        val kinds = svc.timeline(f.viewer, i.key).map { it.kind }
        assertEquals(listOf(EntryKind.STATUS, EntryKind.STATUS, EntryKind.SEVERITY, EntryKind.FIELD, EntryKind.NOTE), kinds)
    }

    @Test
    fun `viewers read but cannot write and stale versions are rejected`() = suspendTest {
        val i = svc.declare(f.member, NewIncident("X", severity = Severity.SEV3, teamIds = listOf(f.team.id)))
        assertFailsWith<Forbidden> { svc.declare(f.viewer, NewIncident("X", severity = Severity.SEV3, teamIds = listOf(f.team.id))) }
        assertFailsWith<Forbidden> { svc.addEntry(f.viewer, i.key, "hi") }
        svc.update(f.member, i.key, IncidentPatch(title = "new"))
        assertFailsWith<mayday.shared.Conflict> { svc.update(f.member, i.key, IncidentPatch(title = "stale", version = 1)) }
    }

    @Test
    fun `incidents need a title and a known team`() = suspendTest {
        assertFailsWith<Invalid> { svc.declare(f.member, NewIncident(" ", severity = Severity.SEV3, teamIds = listOf(f.team.id))) }
        assertFailsWith<mayday.shared.NotFound> { svc.declare(f.member, NewIncident("X", severity = Severity.SEV3, teamIds = listOf("nope"))) }
        assertFailsWith<Invalid> { svc.declare(f.member, NewIncident("X", severity = Severity.SEV3, teamIds = emptyList())) }
    }
}

class AlertTest {
    private val f = Fixture()
    private val svc = f.app.alerts

    @Test
    fun `severe alerts open an incident and duplicates only count`() = suspendTest {
        val r1 = svc.ingest(f.member, AlertSignal("DB down", Severity.SEV1, "checkout"))
        assertTrue(r1.created)
        assertNotNull(r1.incidentKey)
        val r2 = svc.ingest(f.member, AlertSignal("DB down", Severity.SEV1, "checkout"))
        assertFalse(r2.created)
        assertEquals(2, r2.alert.count)
        assertEquals(r1.alert.incidentId, r2.alert.incidentId)
    }

    @Test
    fun `minor alerts do not open incidents`() = suspendTest {
        assertNull(svc.ingest(f.member, AlertSignal("Disk 70%", Severity.SEV4, "checkout")).incidentKey)
    }

    @Test
    fun `resolve signals resolve and a quick refire reopens the same alert`() = suspendTest {
        val a = svc.ingest(f.member, AlertSignal("Flappy", Severity.SEV3, "checkout", fingerprint = "fp1")).alert
        val resolved = svc.ingest(f.member, AlertSignal("Flappy", Severity.SEV3, "checkout", fingerprint = "fp1", status = AlertStatus.RESOLVED))
        assertEquals(AlertStatus.RESOLVED, resolved.alert.status)
        val again = svc.ingest(f.member, AlertSignal("Flappy", Severity.SEV3, "checkout", fingerprint = "fp1"))
        assertEquals(a.id, again.alert.id)
        assertEquals(AlertStatus.FIRING, again.alert.status)
    }

    @Test
    fun `ingest needs an ingest or write scope`() = suspendTest {
        val tok = f.app.identity.issueToken(f.member, "ro", setOf(TokenScope.READ), null)
        val p = f.app.identity.authenticate(tok.secret)
        assertFailsWith<Forbidden> { svc.ingest(p, AlertSignal("x", Severity.SEV3, "checkout")) }
    }
}

class IdentityTest {
    private val f = Fixture()

    @Test
    fun `api tokens authenticate with their scopes and can be revoked`() = suspendTest {
        val t = f.app.identity.issueToken(f.member, "cli", setOf(TokenScope.READ, TokenScope.ADMIN), null)
        val p = f.app.identity.authenticate(t.secret)
        assertEquals(f.memberUser.id, p.user!!.id)
        assertEquals(setOf(TokenScope.READ), p.scopes, "non-admins cannot get the admin scope")
        f.app.identity.revokeToken(f.member, t.token.id)
        assertFailsWith<Unauthenticated> { f.app.identity.authenticate(t.secret) }
    }

    @Test
    fun `delegated tokens carry the user identity and the agent as actor`() = suspendTest {
        val creds = f.app.agents.create(f.admin, AgentInput("Bot", delivery = Delivery.PULL, actions = setOf(AgentAction.ANALYZE), role = OrgRole.VIEWER))
        val agentPrincipal = f.app.identity.authenticate(creds.token!!.secret)
        assertTrue(agentPrincipal.isAgent)
        val subject = f.app.identity.principalForUser(f.memberUser)
        val obo = f.app.identity.exchange(subject, agentPrincipal, Duration.ofMinutes(5))
        val p = f.app.identity.authenticate(obo)
        assertTrue(p.isDelegated)
        assertEquals(f.memberUser.id, p.user!!.id)
        assertEquals(ActorKind.AGENT, p.actor.kind)
        assertEquals(OrgRole.MEMBER, p.role, "the user's RBAC applies, not the agent's")
        assertFailsWith<Forbidden> { f.app.identity.exchange(agentPrincipal, agentPrincipal, Duration.ofMinutes(5)) }
    }

    @Test
    fun `tampered or foreign tokens are rejected`() = suspendTest {
        assertFailsWith<Unauthenticated> { f.app.identity.authenticate("mdy_nope") }
        assertFailsWith<Unauthenticated> { f.app.identity.authenticate("a.b.c") }
    }
}

class AgentTest {
    private val f = Fixture()

    private suspend fun incident() = f.app.incidents.declare(f.member, NewIncident("Checkout down", severity = Severity.SEV2, teamIds = listOf(f.team.id)))

    @Test
    fun `without an LLM provider the built-in agent is not available`() = suspendTest {
        val i = incident()
        assertFailsWith<Unavailable> { f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "") }
    }

    @Test
    fun `manual analysis runs on behalf of the person who pressed the button`() = suspendTest {
        f.enableLlm()
        val i = incident()
        f.llm.toolCall("add_timeline_entry", buildJsonObject { put("ref", i.key); put("text", "Found errors in logs") })
        f.llm.answer("Summary: the pool is exhausted.")
        val run = f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "Focus on the database")
        val done = f.awaitRun(run.id)
        assertEquals(RunStatus.SUCCEEDED, done.status, done.error)
        assertEquals(f.memberUser.id, done.onBehalfOf)
        val entries = f.app.incidents.timeline(f.viewer, i.key)
        val toolEntry = entries.first { it.text == "Found errors in logs" }
        assertEquals(ActorKind.AGENT, toolEntry.actor.kind)
        assertEquals(f.memberUser.id, toolEntry.actor.onBehalfOf)
        assertTrue(entries.any { it.text.startsWith("Summary: the pool") })
        assertTrue(f.llm.requests.first().messages.first().blocks.first().toString().contains("Focus on the database"))
    }

    @Test
    fun `agents cannot do more than the delegating user`() = suspendTest {
        f.enableLlm()
        val i = incident()
        f.llm.toolCall("set_setting", buildJsonObject { put("scope", "ORG"); put("key", Keys.MCP_WRITE); put("value", "true") })
        f.llm.answer("tried")
        val done = f.awaitRun(f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "").id)
        assertEquals("false", f.app.policy.effective(emptyList())[Keys.MCP_WRITE])
        assertTrue(f.llm.requests.first().tools.none { it.name == "set_setting" })
        assertEquals(RunStatus.SUCCEEDED, done.status)
    }

    @Test
    fun `viewers cannot start agents`() = suspendTest {
        f.enableLlm()
        val i = incident()
        assertFailsWith<Forbidden> { f.app.agents.trigger(f.viewer, AgentAction.ANALYZE, SubjectKind.INCIDENT, i.key, null, "") }
    }

    @Test
    fun `push agents receive a delegated token that acts as the user`() = suspendTest {
        val creds = f.app.agents.create(f.admin, AgentInput("Fixer", endpointUrl = "https://agent.example.com/hook", actions = setOf(AgentAction.PATCH)))
        val i = incident()
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, i.key, creds.agent.id, "")
        eventually { f.dispatcher.sent.isNotEmpty() }
        val payload = f.dispatcher.sent.single().second
        assertEquals(run.id, payload.run.id)
        assertEquals("http://mayday.test/mcp", payload.mcpUrl)
        val p = f.app.identity.authenticate(payload.delegatedToken!!)
        assertEquals(f.memberUser.id, p.user!!.id)
        assertEquals(run.id, p.runId)
        f.app.agents.complete(f.app.identity.authenticate(creds.token!!.secret), run.id, true, "PR #12 opened", "")
        assertEquals(RunStatus.SUCCEEDED, f.app.agents.run(f.viewer, run.id).status)
    }

    @Test
    fun `failed deliveries mark the run as failed`() = suspendTest {
        f.dispatcher.fail = true
        val creds = f.app.agents.create(f.admin, AgentInput("Down", endpointUrl = "https://agent.example.com/hook", actions = setOf(AgentAction.ANALYZE)))
        val run = f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, incident().key, creds.agent.id, "")
        assertEquals(RunStatus.FAILED, f.awaitRun(run.id).status)
    }

    @Test
    fun `pull agents claim queued runs and only the owner completes them`() = suspendTest {
        val a = f.app.agents.create(f.admin, AgentInput("Puller", delivery = Delivery.PULL, actions = setOf(AgentAction.RUNBOOK)))
        val b = f.app.agents.create(f.admin, AgentInput("Other", delivery = Delivery.PULL, actions = setOf(AgentAction.RUNBOOK)))
        val run = f.app.agents.trigger(f.member, AgentAction.RUNBOOK, SubjectKind.INCIDENT, incident().key, a.agent.id, "")
        val pa = f.app.identity.authenticate(a.token!!.secret)
        val pb = f.app.identity.authenticate(b.token!!.secret)
        assertTrue(f.app.agents.claim(pb, 5).isEmpty())
        val claimed = f.app.agents.claim(pa, 5).single()
        assertEquals(run.id, claimed.run.id)
        assertNotNull(claimed.delegatedToken)
        assertFailsWith<Forbidden> { f.app.agents.complete(pb, run.id, true, "x", "") }
        f.app.agents.complete(pa, run.id, true, "Runbook saved", "")
        assertFailsWith<mayday.shared.Conflict> { f.app.agents.complete(pa, run.id, true, "again", "") }
    }

    @Test
    fun `alerts trigger automatic analysis only when policy allows`() = suspendTest {
        val creds = f.app.agents.create(f.admin, AgentInput("Auto", delivery = Delivery.PULL, actions = setOf(AgentAction.ANALYZE, AgentAction.REMEDIATE),
            triggers = listOf(AgentTrigger(minSeverity = Severity.SEV3, action = AgentAction.ANALYZE), AgentTrigger(minSeverity = Severity.SEV3, action = AgentAction.REMEDIATE))))
        f.app.alerts.ingest(f.member, AlertSignal("Errors", Severity.SEV2, "checkout"))
        eventually { f.app.agents.recentRuns(f.viewer, 10).any { it.agentId == creds.agent.id } }
        delay(100)
        val runs = f.app.agents.recentRuns(f.viewer, 10).filter { it.agentId == creds.agent.id }
        assertEquals(listOf(AgentAction.ANALYZE), runs.map { it.action }, "remediation is off by default")
        assertNull(runs.single().onBehalfOf)
        f.app.policy.set(f.admin, Scope.ORG, "org", Keys.AGENT_AUTO_ANALYSIS, "false", Enforcement.NONE)
        f.app.alerts.ingest(f.member, AlertSignal("Other errors", Severity.SEV2, "checkout"))
        delay(200)
        assertEquals(1, f.app.agents.recentRuns(f.viewer, 10).count { it.agentId == creds.agent.id })
    }

    @Test
    fun `postmortem and runbook generation write through the tools`() = suspendTest {
        f.enableLlm()
        val i = incident()
        f.llm.toolCall("set_postmortem", buildJsonObject { put("ref", i.key); put("summary", "Pool exhausted"); put("rootCause", "Config change") })
        f.llm.answer("Postmortem drafted.")
        f.awaitRun(f.app.agents.trigger(f.member, AgentAction.POSTMORTEM, SubjectKind.INCIDENT, i.key, null, "").id)
        assertEquals("Pool exhausted", f.app.incidents.get(f.viewer, i.key).postmortem!!.summary)
        f.llm.toolCall("save_runbook", buildJsonObject { put("ref", i.key); put("title", "Restore pool"); put("body", "1. Open the dashboard.") })
        f.llm.answer("Runbook saved.")
        f.awaitRun(f.app.agents.trigger(f.member, AgentAction.RUNBOOK, SubjectKind.INCIDENT, i.key, null, "").id)
        assertEquals("Restore pool", f.app.incidents.get(f.viewer, i.key).runbook!!.title)
        assertTrue(f.llm.requests.last().messages.first().blocks.first().toString().contains("ASD-STE100"))
    }
}
