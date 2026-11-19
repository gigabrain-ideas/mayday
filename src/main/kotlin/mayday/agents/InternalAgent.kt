package mayday.agents

import kotlinx.serialization.json.JsonObject
import mayday.identity.Principal
import mayday.shared.Clock
import mayday.shared.JSON

/** Agent loop of the built-in agent. It uses only provider-neutral ports. */
class InternalAgent(
    private val llm: LlmProvider,
    private val catalog: ToolCatalog,
    private val clock: Clock,
    private val maxTurns: Int = 24,
) {
    fun run(agent: AgentConfig, run: AgentRun, principal: Principal, subject: JsonObject): String {
        val tools = catalog.tools(principal, internal = true)
        val messages = mutableListOf(LlmMessage(LlmRole.USER, listOf(LlmBlock.Text(task(run, subject)))))
        val system = SYSTEM_PROMPT + if (agent.instructions.isNotBlank()) "\n\nOrganization instructions:\n${agent.instructions}" else ""
        var finalText = ""
        for (turn in 0 until maxTurns) {
            val resp = llm.complete(LlmRequest(system, messages, tools))
            messages += resp.message
            val text = resp.message.blocks.filterIsInstance<LlmBlock.Text>().joinToString("\n") { it.text }.trim()
            if (text.isNotEmpty()) finalText = text
            when (resp.stopReason) {
                StopReason.TOOL_USE -> {
                    val results = resp.message.blocks.filterIsInstance<LlmBlock.ToolUse>().map { use ->
                        val outcome = runCatching { catalog.call(principal, use.name, use.input, internal = true) }
                            .getOrElse { ToolOutcome(it.message ?: "Tool failed", true) }
                        LlmBlock.ToolResult(use.id, outcome.content.take(60_000), outcome.isError)
                    }
                    messages += LlmMessage(LlmRole.USER, results)
                }
                StopReason.REFUSAL -> throw IllegalStateException("The model declined this request")
                StopReason.MAX_TOKENS -> return finalText.ifEmpty { "The response reached the token limit." }
                else -> return finalText
            }
        }
        return finalText.ifEmpty { "Stopped after $maxTurns steps." }
    }

    private fun task(run: AgentRun, subject: JsonObject): String {
        val ref = run.subjectLabel
        val goal = when (run.action) {
            AgentAction.ANALYZE -> "Analyze $ref. Find the probable cause, the impact and the next steps. Use the available tools to collect evidence. Give your result as a short report with the headings Summary, Evidence, Probable cause, Next steps."
            AgentAction.REMEDIATE -> "Try to mitigate $ref with the available tools. Do only safe and reversible actions. Record each action on the timeline with add_timeline_entry. Finish with a report of what you did and what is still necessary."
            AgentAction.PATCH -> "Prepare a patch or bugfix for the cause of $ref with the available tools (for example a pull request). Do not merge or deploy it. Finish with a link to the patch and a short description of the change and its risks."
            AgentAction.TIMELINE -> "Make the timeline of $ref complete. Compare the alerts and updates with the timeline. Add each missing important event with add_timeline_entry and its real time in 'at'. Do not add duplicates. Finish with a one-line summary."
            AgentAction.POSTMORTEM -> "Write the postmortem of $ref from all available data. Call set_postmortem once with summary, impact, rootCause, detection, resolution, lessons and actionItems. Keep it blameless and factual. Finish with a one-line summary."
            AgentAction.RUNBOOK -> "Write a runbook from the resolution notes and timeline of $ref. Use ASD-STE100 Simplified Technical English: short sentences, active voice, one instruction per step, imperative verbs. Use numbered steps in Markdown with sections Symptoms, Checks, Procedure, Verification, Escalation. Call save_runbook with the title and body. Finish with a one-line summary."
        }
        val human = if (run.prompt.isNotBlank()) "\n\nInstructions from the person who started this run:\n${run.prompt}" else ""
        return "Current time: ${clock.now()}\nTask: $goal$human\n\nSubject data (JSON):\n${JSON.encodeToString(JsonObject.serializer(), subject)}"
    }

    companion object {
        val SYSTEM_PROMPT = """
            You are the incident agent of Mayday, an incident management platform. You help responders during and after incidents.
            Rules:
            - Use the tools to read data before you make a statement. Do not invent facts, times, systems or people.
            - When data is missing, say so and tell which data is necessary.
            - Every change you make is recorded with your identity. When you act on behalf of a person, their permissions apply.
            - If a tool returns a permission error, stop that action and report it.
            - Write in a professional and friendly tone. Use short sentences. Prefer lists.
        """.trimIndent()
    }
}
