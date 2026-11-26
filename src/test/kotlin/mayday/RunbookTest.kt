package mayday

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mayday.agents.AgentRun
import mayday.agents.LlmBlock
import mayday.agents.LlmMessage
import mayday.agents.LlmResponse
import mayday.agents.LlmRole
import mayday.agents.RunStatus
import mayday.agents.RunTrigger
import mayday.agents.StopReason
import mayday.agents.SubjectKind
import mayday.agents.AgentAction
import mayday.alerting.AlertSignal
import mayday.alerting.AlertStatus
import mayday.alerting.waitsForHuman
import mayday.escalation.EscalationLevel
import mayday.escalation.EscalationPolicyInput
import mayday.escalation.EscalationStatus
import mayday.escalation.EscalationTarget
import mayday.escalation.TargetKind
import mayday.identity.OrgRole
import mayday.identity.Principal
import mayday.identity.TokenScope
import mayday.oncall.ScheduleInput
import mayday.oncall.ShiftUnit
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.runbooks.AlertMatch
import mayday.runbooks.Runbook
import mayday.runbooks.RunbookInput
import mayday.runbooks.RunbookStatus
import mayday.shared.Actor
import mayday.shared.ActorKind
import mayday.shared.Conflict
import mayday.shared.Forbidden
import mayday.shared.Ids
import mayday.shared.Invalid
import mayday.shared.Severity
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunbookTest {
    private val clock = TestClock()
    private val f = Fixture(clock = clock)
    private val runbooks = f.app.runbooks
    private val esc = f.app.escalation

    private val automaticAgent = Principal(Actor(ActorKind.AGENT, "mayday", "Mayday agent"), null, OrgRole.MEMBER, setOf(TokenScope.READ, TokenScope.WRITE), "run-x")

    private fun input(source: String = "prometheus", tools: List<String> = listOf(Runbook.RESOLVE), labels: Map<String, String> = emptyMap(), team: String = f.team.id) =
        RunbookInput(team, "Disk full", "1. Delete old logs.\n2. Verify that disk use is below 80%.", AlertMatch(source, labels), tools)

    private suspend fun approved(input: RunbookInput = input(), by: Principal = f.lead) = runbooks.create(f.member, input).let { runbooks.approve(by, it.id, it.version) }

    private suspend fun policy() {
        val s = f.app.onCall.create(f.lead, ScheduleInput(f.team.id, "Primary", "UTC", clock.now().minus(Duration.ofDays(1)), 1, ShiftUnit.WEEKS, listOf(f.teamAdminUser.id)))
        esc.create(f.lead, EscalationPolicyInput(f.team.id, "Default", levels = listOf(EscalationLevel(5, listOf(EscalationTarget(TargetKind.SCHEDULE, s.id))),
            EscalationLevel(10, listOf(EscalationTarget(TargetKind.USER, f.memberUser.id)))), teamDefault = true))
    }

    private suspend fun fire(source: String = "prometheus", severity: Severity = Severity.SEV2, labels: Map<String, String> = emptyMap()) =
        f.app.alerts.ingest(f.admin, AlertSignal("Disk 95% on db-1", severity, "checkout", source = source, labels = labels)).alert

    /** The built-in agent calls [tool] on the alert that the incident in its subject data links. */
    private fun FakeLlm.callOnSubjectAlert(tool: String) = synchronized(script) {
        script.addLast { req ->
            val data = req.messages.first().blocks.filterIsInstance<LlmBlock.Text>().last().text
            val id = Regex("\"alertIds\":\\[\"([0-9a-f]+)\"").find(data)?.groupValues?.get(1) ?: Regex("\"alert\":\\{\"id\":\"([0-9a-f]+)\"").find(data)!!.groupValues[1]
            LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.ToolUse(Ids.new(), tool, buildJsonObject { put("id", id) }))), StopReason.TOOL_USE)
        }
    }

    private suspend fun autoRun(): AgentRun {
        eventually { f.app.agents.recentRuns(f.viewer, 10).any { it.agentId == "mayday" && it.status.terminal } }
        return f.app.agents.recentRuns(f.viewer, 10).single { it.agentId == "mayday" }
    }

    // ---- approval ----

    @Test
    fun `members draft runbooks and only a team owner approves them, as a person, in the version they read`() = suspendTest {
        assertFailsWith<Forbidden> { runbooks.create(f.viewer, input()) }
        val d = runbooks.create(f.member, input())
        assertEquals(RunbookStatus.DRAFT, d.status)
        assertFailsWith<Forbidden> { runbooks.approve(f.member, d.id, d.version) }
        val otherOwner = f.app.identity.principalForUser(f.teamAdminUser.copy(teams = mapOf(f.other.id to mayday.identity.TeamRole.ADMIN)))
        assertFailsWith<Forbidden> { runbooks.approve(otherOwner, d.id, d.version) }
        val delegated = Principal(Actor(ActorKind.AGENT, "mayday", "Mayday agent", onBehalfOf = f.adminUser.id), f.adminUser, OrgRole.ADMIN, Principal.ALL, "run-1")
        assertFailsWith<Forbidden> { runbooks.approve(delegated, d.id, d.version) }
        val agentAdmin = Principal(Actor(ActorKind.AGENT, "ops", "Ops agent"), null, OrgRole.ADMIN, Principal.ALL)
        assertFailsWith<Forbidden> { runbooks.approve(agentAdmin, d.id, d.version) }
        assertFailsWith<Conflict> { runbooks.approve(f.lead, d.id, d.version + 1) }
        val ok = runbooks.approve(f.lead, d.id, d.version)
        assertEquals(RunbookStatus.APPROVED, ok.status)
        assertEquals(f.teamAdminUser.id, ok.approvedBy?.id)
        assertNotNull(ok.approvedAt)
        assertFailsWith<Conflict> { runbooks.approve(f.admin, ok.id, ok.version) }
    }

    @Test
    fun `a change of an approved runbook needs a team owner and a new approval`() = suspendTest {
        val rb = approved()
        assertFailsWith<Forbidden> { runbooks.update(f.member, rb.id, input(tools = listOf(Runbook.RESOLVE, "restart_pod")), rb.version) }
        assertFailsWith<Conflict> { runbooks.update(f.lead, rb.id, input(), rb.version - 1) }
        val changed = runbooks.update(f.lead, rb.id, input(tools = listOf(Runbook.RESOLVE, "restart_pod")), rb.version)
        assertEquals(RunbookStatus.DRAFT, changed.status)
        assertNull(changed.approvedBy)
        assertNull(runbooks.matchFor(fire()))
    }

    @Test
    fun `a runbook names the type of alert and real tool names`() = suspendTest {
        assertFailsWith<Invalid> { runbooks.create(f.member, input(source = "")) }
        assertFailsWith<Invalid> { runbooks.create(f.member, input(tools = listOf("rm -rf /"))) }
        assertFailsWith<Invalid> { runbooks.create(f.member, input().copy(title = " ")) }
        assertFailsWith<Invalid> { runbooks.create(f.member, input().copy(body = "")) }
        assertFailsWith<mayday.shared.NotFound> { runbooks.create(f.member, input(team = "missing")) }
    }

    @Test
    fun `only an approved runbook of the alert team covers the alert and the most specific one wins`() = suspendTest {
        runbooks.create(f.member, input(source = "prometheus"))
        assertNull(runbooks.matchFor(fire()), "a draft covers nothing")
        approved(input(team = f.other.id), f.admin)
        assertNull(runbooks.matchFor(fire()), "a runbook of another team covers nothing")
        val broad = approved(input(source = "prometheus"))
        val narrow = approved(input(source = "prometheus", labels = mapOf("service" to "db")))
        assertEquals(broad.id, runbooks.matchFor(fire(labels = mapOf("service" to "web")))?.id)
        assertEquals(narrow.id, runbooks.matchFor(fire(labels = mapOf("service" to "db")))?.id)
        assertNull(runbooks.matchFor(fire(source = "grafana")))
        runbooks.revoke(f.lead, broad.id)
        assertNull(runbooks.matchFor(fire(labels = mapOf("service" to "web"))), "a revoked runbook covers nothing")
        val fp = approved(RunbookInput(f.team.id, "By fingerprint", "1. Check.", AlertMatch(fingerprint = "disk-*")))
        val a = f.app.alerts.ingest(f.admin, AlertSignal("Disk", Severity.SEV3, "checkout", fingerprint = "disk-db-1")).alert
        assertEquals(fp.id, runbooks.matchFor(a)?.id)
    }

    // ---- resolve with no person ----

    @Test
    fun `the built-in agent follows an approved runbook, resolves the alert and paging stops`() = suspendTest {
        policy()
        val rb = approved()
        f.enableLlm()
        f.llm.callOnSubjectAlert("resolve_alert")
        f.llm.answer("Old logs deleted. Disk at 40%.")
        val alert = fire()
        val run = autoRun()
        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals(rb.id, run.runbookId)
        assertEquals(alert.id, run.alertId)
        val task = (f.llm.requests.first().messages.first().blocks.first() as LlmBlock.Text).text
        assertTrue("Delete old logs." in task, "the approved procedure is part of the task")
        val tools = f.llm.requests.first().tools.map { it.name }
        assertTrue("resolve_alert" in tools && "raise_to_human" in tools)
        assertFalse("update_incident" in tools, "tools outside the runbook are not offered")
        val done = f.app.alerts.get(f.viewer, alert.id)
        assertEquals(AlertStatus.RESOLVED, done.status)
        assertEquals(rb.id, done.resolvedUnderRunbook)
        assertFalse(done.waitsForHuman)
        assertEquals(EscalationStatus.RESOLVED, esc.escalation(f.viewer, alert.id).status)
        val pages = f.pager.pages.size
        clock.advance(Duration.ofMinutes(30))
        esc.tick()
        assertEquals(pages, f.pager.pages.size, "nobody is paged again")
    }

    @Test
    fun `an automated agent resolves with no runbook and paging goes on until a person acknowledges`() = suspendTest {
        policy()
        val alert = fire()
        val resolved = f.app.alerts.resolve(automaticAgent, alert.id, "Looks fine")
        assertEquals(AlertStatus.RESOLVED, resolved.status)
        assertEquals("mayday", resolved.resolvedBy?.id)
        assertNull(resolved.resolvedUnderRunbook)
        assertTrue(resolved.waitsForHuman)
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, alert.id).status)
        assertEquals(resolved, f.app.alerts.acknowledge(automaticAgent, alert.id), "an automated agent cannot confirm its own resolve")
        clock.advance(Duration.ofMinutes(5))
        esc.tick()
        assertEquals(listOf(f.memberUser.id), f.pager.pages.last().recipients.map { it.id }, "the next level is paged")
        val confirmed = f.app.alerts.acknowledge(f.member, alert.id)
        assertEquals(AlertStatus.RESOLVED, confirmed.status)
        assertFalse(confirmed.waitsForHuman)
        assertEquals(EscalationStatus.RESOLVED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `a person resolves after an automated agent and paging stops`() = suspendTest {
        policy()
        val alert = fire()
        f.app.alerts.resolve(automaticAgent, alert.id)
        val byPerson = f.app.alerts.resolve(f.member, alert.id, "Checked the dashboards")
        assertEquals(f.memberUser.id, byPerson.resolvedBy?.id)
        assertEquals(EscalationStatus.RESOLVED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `the monitoring source confirms a resolve by an automated agent and paging stops`() = suspendTest {
        policy()
        val alert = fire()
        f.app.alerts.resolve(automaticAgent, alert.id)
        val r = f.app.alerts.ingest(f.admin, AlertSignal("Disk 95% on db-1", Severity.SEV2, "checkout", source = "prometheus", status = AlertStatus.RESOLVED))
        assertEquals(alert.id, r.alert.id)
        assertEquals(ActorKind.SYSTEM, r.alert.resolvedBy?.kind)
        assertFalse(r.alert.waitsForHuman)
        assertEquals(EscalationStatus.RESOLVED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `a resolve after a person acknowledged needs no confirmation`() = suspendTest {
        policy()
        val alert = fire()
        f.app.alerts.acknowledge(f.member, alert.id)
        val r = f.app.alerts.resolve(automaticAgent, alert.id)
        assertFalse(r.waitsForHuman)
        assertEquals(EscalationStatus.ACKNOWLEDGED, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `an automated agent closes a declared incident and its alerts keep paging`() = suspendTest {
        policy()
        val inc = f.app.incidents.declare(f.member, mayday.incidents.NewIncident("Checkout down", severity = Severity.SEV2, teamIds = listOf(f.team.id)))
        f.app.incidents.update(automaticAgent, inc.key, mayday.incidents.IncidentPatch(status = mayday.incidents.IncidentStatus.RESOLVED))
        val alert = f.app.alerts.get(f.viewer, inc.alertIds.single())
        assertEquals(AlertStatus.RESOLVED, alert.status)
        assertTrue(alert.waitsForHuman)
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, alert.id).status)
    }

    @Test
    fun `only an active run of the same agent on the same alert, under a runbook that is still approved, covers a resolve`() = suspendTest {
        val rb = approved()
        val alert = fire(severity = Severity.SEV3)
        val agent = automaticAgent.actor
        suspend fun run(status: RunStatus, agentId: String = "mayday", alertId: String = alert.id) = AgentRun(Ids.new(), agentId, "Agent", AgentAction.ANALYZE,
            SubjectKind.ALERT, alertId, "x", status, RunTrigger.AUTO, Actor.system, createdAt = clock.now(), alertId = alertId, runbookId = rb.id).also { f.store.runs.save(it, null) }
        run(RunStatus.SUCCEEDED)
        run(RunStatus.RUNNING, agentId = "other")
        assertNull(f.app.agents.runbookFor(alert, agent), "a finished run or another agent covers nothing")
        run(RunStatus.RUNNING)
        assertEquals(rb.id, f.app.agents.runbookFor(alert, agent))
        assertNull(f.app.agents.runbookFor(alert, agent.copy(onBehalfOf = f.memberUser.id)), "an agent for a person needs no runbook")
        runbooks.revoke(f.lead, rb.id)
        assertNull(f.app.agents.runbookFor(alert, agent), "a revoked runbook covers nothing")
    }

    @Test
    fun `a runbook without resolve_alert never lets the agent resolve alone`() = suspendTest {
        policy()
        approved(input(tools = listOf("add_alert_update")))
        val alert = fire(severity = Severity.SEV3)
        val r = AgentRun(Ids.new(), "mayday", "Mayday agent", AgentAction.ANALYZE, SubjectKind.ALERT, alert.id, "x", RunStatus.RUNNING, RunTrigger.AUTO, Actor.system,
            createdAt = clock.now(), alertId = alert.id, runbookId = runbooks.matchFor(alert)!!.id)
        f.store.runs.save(r, null)
        assertTrue(f.app.alerts.resolve(automaticAgent, alert.id).waitsForHuman)
    }

    // ---- raise to a person ----

    @Test
    fun `an agent that leaves the runbook is refused and the alert pages a person`() = suspendTest {
        policy()
        f.app.policy.set(f.admin, Scope.ORG, "org", Keys.ESCALATION_MIN_SEVERITY, "SEV1", Enforcement.NONE)
        approved()
        f.enableLlm()
        f.llm.callOnSubjectAlert("acknowledge_alert")
        f.llm.answer("stopped")
        val alert = fire()
        assertTrue(f.pager.pages.isEmpty(), "a SEV2 does not page with page_min_severity SEV1")
        val run = autoRun()
        assertNotNull(run.raisedAt)
        val after = f.app.alerts.get(f.viewer, alert.id)
        assertEquals(AlertStatus.FIRING, after.status, "the refused tool changed nothing")
        assertTrue(after.updates.any { it.text.startsWith("Raised to a person") && "acknowledge_alert" in it.text })
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, alert.id).status)
        assertEquals(listOf(f.teamAdminUser.id), f.pager.pages.single().recipients.map { it.id })
        assertTrue(f.auditSink.records.any { it.action == "run.raised_to_human" })
    }

    @Test
    fun `an agent raises a case that the runbook does not cover`() = suspendTest {
        policy()
        approved()
        f.enableLlm()
        synchronized(f.llm.script) {
            f.llm.script.addLast {
                LlmResponse(LlmMessage(LlmRole.ASSISTANT, listOf(LlmBlock.ToolUse(Ids.new(), "raise_to_human", buildJsonObject { put("reason", "The disk is full of database files, not logs.") }))), StopReason.TOOL_USE)
            }
        }
        f.llm.answer("Raised.")
        val alert = fire()
        val run = autoRun()
        assertNotNull(run.raisedAt)
        assertTrue(f.app.alerts.get(f.viewer, alert.id).updates.any { "database files" in it.text })
    }

    @Test
    fun `a failed runbook run raises the alert to a person once`() = suspendTest {
        policy()
        approved()
        f.enableLlm()
        f.llm.fail(IllegalStateException("model unavailable"))
        val alert = fire()
        val run = autoRun()
        assertEquals(RunStatus.FAILED, run.status)
        eventually { f.store.runs.get(run.id)!!.raisedAt != null }
        assertEquals(1, f.app.alerts.get(f.viewer, alert.id).updates.count { it.text.startsWith("Raised to a person") })
    }

    @Test
    fun `a raise after a resolve under the runbook makes a person confirm the resolve`() = suspendTest {
        policy()
        val rb = approved()
        val alert = fire(severity = Severity.SEV3)
        val r = AgentRun(Ids.new(), "mayday", "Mayday agent", AgentAction.ANALYZE, SubjectKind.ALERT, alert.id, "x", RunStatus.RUNNING, RunTrigger.AUTO, Actor.system,
            createdAt = clock.now(), alertId = alert.id, runbookId = rb.id)
        f.store.runs.save(r, null)
        val resolved = f.app.alerts.resolve(automaticAgent, alert.id)
        assertEquals(rb.id, resolved.resolvedUnderRunbook)
        assertEquals(EscalationStatus.RESOLVED, esc.escalation(f.viewer, alert.id).status)
        val raised = f.app.alerts.raiseToHuman(alert.id, automaticAgent.actor, "Verification failed after the resolve")!!
        assertTrue(raised.waitsForHuman)
        assertEquals(EscalationStatus.ACTIVE, esc.escalation(f.viewer, alert.id).status)
    }
}
