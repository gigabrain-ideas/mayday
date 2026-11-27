package mayday

import mayday.agents.AgentAction
import mayday.agents.LlmBlock
import mayday.agents.LlmRequest
import mayday.agents.SubjectKind
import mayday.incidents.NewIncident
import mayday.shared.Severity
import kotlin.test.Test
import kotlin.test.assertTrue

/** The built-in agent names a cause only with evidence. Without evidence it tells which instrumentation is necessary to find the cause. */
class EvidenceRulesTest {
    private val f = Fixture()

    private suspend fun requestFor(action: AgentAction): LlmRequest {
        f.enableLlm()
        val i = f.app.incidents.declare(f.member, NewIncident("Checkout down", severity = Severity.SEV2, teamIds = listOf(f.team.id)))
        val before = f.llm.requests.size
        f.llm.answer("done")
        f.app.agents.trigger(f.member, action, SubjectKind.INCIDENT, i.key, null, "")
        eventually { f.llm.requests.size > before }
        return f.llm.requests.last()
    }

    private fun LlmRequest.task() = messages.first().blocks.filterIsInstance<LlmBlock.Text>().first { "Task:" in it.text }.text.lowercase()

    @Test
    fun `the system prompt forbids a root cause without evidence and asks for instrumentation guidance`() = suspendTest {
        val system = requestFor(AgentAction.ANALYZE).system.lowercase()
        assertTrue("root cause" in system && "evidence" in system, "the system prompt has no rule about root causes and evidence")
        assertTrue("instrumentation" in system, "the system prompt does not ask for instrumentation guidance")
    }

    @Test
    fun `cause-related tasks tell the agent what to do when evidence is missing`() = suspendTest {
        for (action in listOf(AgentAction.ANALYZE, AgentAction.PATCH, AgentAction.POSTMORTEM)) {
            val task = requestFor(action).task()
            assertTrue("instrumentation" in task, "the $action task does not say what to do without evidence: $task")
        }
    }
}
