package mayday

import mayday.agents.AgentAction
import mayday.agents.AgentInput
import mayday.agents.RunStatus
import mayday.agents.SubjectKind
import mayday.identity.Principal
import mayday.incidents.NewIncident
import mayday.policy.Enforcement
import mayday.policy.Keys
import mayday.policy.Scope
import mayday.shared.Conflict
import mayday.shared.Forbidden
import mayday.shared.Severity
import mayday.shared.Unauthenticated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `agents.patch_requires_approval`: a patch from an agent is not done until a person approves it. */
class PatchApprovalTest {
    private val f = Fixture()

    private suspend fun incident() = f.app.incidents.declare(f.member, NewIncident("Checkout down", severity = Severity.SEV2, teamIds = listOf(f.team.id)))

    private suspend fun patchAgent() = f.app.agents.create(f.admin, AgentInput("Fixer", endpointUrl = "https://agent.example.com/hook", actions = setOf(AgentAction.PATCH)))

    private suspend fun awaitStatus(id: String, status: RunStatus) =
        eventually(message = "run $id did not reach $status") { f.store.runs.get(id)!!.status == status }

    @Test
    fun `a reported patch waits for approval by default and the agent loses its token`() = suspendTest {
        val creds = patchAgent()
        val i = incident()
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, i.key, creds.agent.id, "")
        assertTrue(run.requiresApproval)
        eventually { f.dispatcher.sent.isNotEmpty() }
        val payload = f.dispatcher.sent.single().second
        assertTrue(payload.run.requiresApproval)
        val agent = f.app.identity.authenticate(creds.token!!.secret)

        val waiting = f.app.agents.complete(agent, run.id, true, "PR #12 opened", "")
        assertEquals(RunStatus.AWAITING_APPROVAL, waiting.status)
        assertFailsWith<Unauthenticated> { f.app.identity.authenticate(payload.delegatedToken!!) }
        assertFailsWith<Conflict> { f.app.agents.complete(agent, run.id, true, "merged", "") }
        assertFailsWith<Conflict> { f.app.agents.progress(agent, run.id, "merging") }
        assertTrue(f.app.incidents.timeline(f.viewer, i.key).any { it.text.contains("PR #12 opened") && it.text.contains("approval") })
    }

    @Test
    fun `only a person with write access approves or rejects a patch`() = suspendTest {
        val creds = patchAgent()
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, incident().key, creds.agent.id, "")
        eventually { f.dispatcher.sent.isNotEmpty() }
        val agent = f.app.identity.authenticate(creds.token!!.secret)
        f.app.agents.complete(agent, run.id, true, "PR #12 opened", "")

        assertFailsWith<Forbidden> { f.app.agents.approve(agent, run.id) }
        assertFailsWith<Forbidden> { f.app.agents.approve(f.viewer, run.id) }
        assertFailsWith<Forbidden> { f.app.agents.approve(Principal.system, run.id) }

        val approved = f.app.agents.approve(f.lead, run.id)
        assertEquals(RunStatus.SUCCEEDED, approved.status)
        assertEquals(f.teamAdminUser.id, approved.approvedBy?.id)
        assertNotNull(approved.approvedAt)
        assertFailsWith<Conflict> { f.app.agents.approve(f.lead, run.id) }
        assertFailsWith<Conflict> { f.app.agents.reject(f.lead, run.id, "too late") }
        eventually { f.auditSink.records.any { it.action == "run.approved" && it.target?.id == run.id } }
    }

    @Test
    fun `a rejected patch ends as rejected with the reason`() = suspendTest {
        val creds = patchAgent()
        val i = incident()
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, i.key, creds.agent.id, "")
        eventually { f.dispatcher.sent.isNotEmpty() }
        f.app.agents.complete(f.app.identity.authenticate(creds.token!!.secret), run.id, true, "PR #12 opened", "")

        val rejected = f.app.agents.reject(f.member, run.id, "Changes the wrong service")
        assertEquals(RunStatus.REJECTED, rejected.status)
        assertTrue(rejected.status.terminal)
        assertTrue(rejected.error.contains("Changes the wrong service"))
        assertTrue(f.app.incidents.timeline(f.viewer, i.key).any { it.text.contains("rejected") && it.text.contains("Changes the wrong service") })
        eventually { f.auditSink.records.any { it.action == "run.rejected" && it.target?.id == run.id } }
    }

    @Test
    fun `runs that wait for approval are not reaped as stale`() = suspendTest {
        val creds = patchAgent()
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, incident().key, creds.agent.id, "")
        eventually { f.dispatcher.sent.isNotEmpty() }
        f.app.agents.complete(f.app.identity.authenticate(creds.token!!.secret), run.id, true, "PR #12 opened", "")
        f.app.agents.reapStale(java.time.Duration.ZERO)
        assertEquals(RunStatus.AWAITING_APPROVAL, f.app.agents.run(f.viewer, run.id).status)
    }

    @Test
    fun `with the policy off a reported patch is done at once`() = suspendTest {
        f.app.policy.set(f.admin, Scope.TEAM, f.team.id, Keys.AGENT_PATCH_APPROVAL, "false", Enforcement.NONE)
        val creds = patchAgent()
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, incident().key, creds.agent.id, "")
        assertFalse(run.requiresApproval)
        eventually { f.dispatcher.sent.isNotEmpty() }
        val done = f.app.agents.complete(f.app.identity.authenticate(creds.token!!.secret), run.id, true, "PR #12 merged", "")
        assertEquals(RunStatus.SUCCEEDED, done.status)
    }

    @Test
    fun `the strictest team applies to an incident of many teams`() = suspendTest {
        f.app.policy.set(f.admin, Scope.TEAM, f.team.id, Keys.AGENT_PATCH_APPROVAL, "false", Enforcement.NONE)
        val creds = patchAgent()
        val i = f.app.incidents.declare(f.member, NewIncident("Both", severity = Severity.SEV2, teamIds = listOf(f.team.id, f.other.id)))
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, i.key, creds.agent.id, "")
        assertTrue(run.requiresApproval)
    }

    @Test
    fun `other actions never wait for approval`() = suspendTest {
        val creds = f.app.agents.create(f.admin, AgentInput("Analyst", endpointUrl = "https://agent.example.com/hook", actions = setOf(AgentAction.ANALYZE)))
        val run = f.app.agents.trigger(f.member, AgentAction.ANALYZE, SubjectKind.INCIDENT, incident().key, creds.agent.id, "")
        assertFalse(run.requiresApproval)
        eventually { f.dispatcher.sent.isNotEmpty() }
        val done = f.app.agents.complete(f.app.identity.authenticate(creds.token!!.secret), run.id, true, "Root cause found", "")
        assertEquals(RunStatus.SUCCEEDED, done.status)
    }

    @Test
    fun `the built-in agent is told not to merge and its patch waits for approval`() = suspendTest {
        f.enableLlm()
        f.llm.answer("Opened PR #7")
        val run = f.app.agents.trigger(f.member, AgentAction.PATCH, SubjectKind.INCIDENT, incident().key, null, "")
        awaitStatus(run.id, RunStatus.AWAITING_APPROVAL)
        val task = f.llm.requests.first().messages.first().blocks.first().toString()
        assertTrue(task.contains("Do not merge or deploy"), task)
        assertTrue(task.contains("approve"), task)
    }
}
